package ai.pleos.quietdrive;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Capability-gated access to optional Android Automotive APIs.
 *
 * <p>This class intentionally compiles without android.car on the class path. Reflection is used
 * only to call named framework API methods when the optional android.car library is present. It
 * never attempts to grant permissions or access hidden fields.</p>
 */
// Lint treats every android.* Class.forName call as a private-API attempt. These names and
// members belong to the documented optional android.car SDK; capability failures stay closed.
@SuppressLint("PrivateApi")
public final class VehicleBridge implements AutoCloseable {
    public interface Listener {
        void onGear(boolean reverse, boolean parked);
        void onFault(String message);
    }

    private static final String AUDIO_PERMISSION_FALLBACK =
            "android.car.permission.CAR_CONTROL_AUDIO_VOLUME";
    private static final String POWERTRAIN_PERMISSION_FALLBACK =
            "android.car.permission.CAR_POWERTRAIN";

    private final Object lock = new Object();
    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int generation;
    private boolean closed = true;
    private Object car;
    private Object audioManager;
    private Object propertyManager;
    private Object gearCallback;
    private int mediaGroupId = -1;
    private boolean audioCapable;
    private boolean gearReadable;
    private boolean parkingReadable;
    private String audioStatus = "차량 오디오 확인 전";
    private String gearStatus = "기어 상태 확인 전";

    public VehicleBridge(Context context, Listener listener) {
        if (context == null || listener == null) {
            throw new IllegalArgumentException("context and listener are required");
        }
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Starts a fresh, non-blocking capability probe. */
    public void connect() {
        final int probeGeneration;
        synchronized (lock) {
            closeLocked();
            closed = false;
            probeGeneration = ++generation;
            audioStatus = "차량 오디오 확인 중";
            gearStatus = "기어 상태 확인 중";
        }
        Thread thread = new Thread(() -> probe(probeGeneration), "QuietDrive-CarProbe");
        thread.start();
    }

    @Override
    public void close() {
        synchronized (lock) {
            generation++;
            closed = true;
            closeLocked();
            audioStatus = "차량 오디오 연결 종료";
            gearStatus = "기어 연결 종료";
        }
    }

    public boolean canControlMedia() {
        synchronized (lock) {
            if (!audioCapable) return false;
            if (!carConnectedLocked()) {
                disableAudioLocked("차량 서비스 연결이 끊김");
                fault("차량 서비스 연결이 끊겨 미디어 음량 제어를 중단함");
                return false;
            }
            if (!hasPermission(audioPermission())) {
                disableAudioLocked("차량 미디어 음량 권한이 해제됨");
                fault("차량 미디어 음량 권한이 해제되어 제어를 중단함");
                return false;
            }
            return true;
        }
    }

    public boolean canReadGear() {
        synchronized (lock) {
            if (!gearReadable) return false;
            if (!carConnectedLocked()) {
                disableGearLocked("차량 서비스 연결이 끊김");
                fault("차량 서비스 연결이 끊겨 기어 감지를 중단함");
                return false;
            }
            if (!hasPermission(powertrainPermission())) {
                disableGearLocked("기어 읽기 권한이 해제됨");
                fault("기어 읽기 권한이 해제되어 감지를 중단함");
                return false;
            }
            return true;
        }
    }

    public boolean canReadParking() {
        synchronized (lock) {
            return parkingReadable && canReadGear();
        }
    }

    public String audioStatus() {
        synchronized (lock) {
            return audioStatus;
        }
    }

    public String gearStatus() {
        synchronized (lock) {
            return gearStatus;
        }
    }

    public int volume() {
        return readAudioIndex("getGroupVolume");
    }

    public int maxVolume() {
        return readAudioIndex("getGroupMaxVolume");
    }

    public int minVolume() {
        return readAudioIndex("getGroupMinVolume");
    }

    public void setMediaVolume(int value) {
        Object manager;
        int group;
        int minimum;
        int maximum;
        synchronized (lock) {
            if (!canControlMedia()) {
                fault("차량 미디어 음량 제어를 사용할 수 없음");
                return;
            }
            manager = audioManager;
            group = mediaGroupId;
        }
        try {
            minimum = invokeInt(manager, "getGroupMinVolume", new Class<?>[]{int.class}, group);
            maximum = invokeInt(manager, "getGroupMaxVolume", new Class<?>[]{int.class}, group);
            int clamped = Math.max(minimum, Math.min(maximum, value));
            method(manager.getClass(), "setGroupVolume", int.class, int.class, int.class)
                    .invoke(manager, group, clamped, 0);
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (lock) {
                disableAudioLocked("차량 미디어 음량 쓰기 실패");
            }
            fault("차량 미디어 음량 쓰기 실패: " + causeName(error));
        }
    }

    private void probe(int probeGeneration) {
        Object candidateCar = null;
        try {
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                finishUnsupported(probeGeneration, "Android Automotive 기기가 아님");
                return;
            }
            Class<?> carClass = Class.forName("android.car.Car");
            candidateCar = method(carClass, "createCar", Context.class).invoke(null, context);
            if (candidateCar == null
                    || !((Boolean) method(carClass, "isConnected").invoke(candidateCar))) {
                finishUnsupported(probeGeneration, "차량 서비스에 연결되지 않음");
                disconnect(candidateCar);
                return;
            }
            synchronized (lock) {
                if (!accepts(probeGeneration)) {
                    disconnect(candidateCar);
                    return;
                }
                car = candidateCar;
            }
            probeAudio(carClass, candidateCar, probeGeneration);
            probeGear(carClass, candidateCar, probeGeneration);
        } catch (ReflectiveOperationException | RuntimeException error) {
            boolean report;
            synchronized (lock) {
                report = accepts(probeGeneration);
                if (accepts(probeGeneration)) {
                    disableAudioLocked("차량 API 확인 실패");
                    disableGearLocked("차량 API 확인 실패");
                }
            }
            if (report) fault("차량 API 확인 실패: " + causeName(error));
            if (candidateCar != null && candidateCar != car) {
                disconnect(candidateCar);
            }
        }
    }

    private void probeAudio(Class<?> carClass, Object candidateCar, int probeGeneration) {
        String permission = stringField(carClass, "PERMISSION_CAR_CONTROL_AUDIO_VOLUME",
                AUDIO_PERMISSION_FALLBACK);
        if (!hasPermission(permission)) {
            synchronized (lock) {
                if (accepts(probeGeneration)) {
                    disableAudioLocked("제조사 승인 차량 오디오 권한 없음");
                }
            }
            return;
        }
        try {
            String service = stringField(carClass, "AUDIO_SERVICE", "audio");
            Object manager = method(carClass, "getCarManager", String.class)
                    .invoke(candidateCar, service);
            if (manager == null) {
                throw new IllegalStateException("audio manager unavailable");
            }
            int group = invokeInt(manager, "getVolumeGroupIdForUsage",
                    new Class<?>[]{int.class}, AudioAttributes.USAGE_MEDIA);
            int[] usages = (int[]) method(manager.getClass(), "getUsagesForVolumeGroupId", int.class)
                    .invoke(manager, group);
            if (!isMediaOnly(usages)) {
                synchronized (lock) {
                    if (accepts(probeGeneration)) {
                        disableAudioLocked("미디어와 안내/경고가 같은 음량 그룹을 사용함");
                    }
                }
                return;
            }
            int minimum = invokeInt(manager, "getGroupMinVolume", new Class<?>[]{int.class}, group);
            int maximum = invokeInt(manager, "getGroupMaxVolume", new Class<?>[]{int.class}, group);
            int current = invokeInt(manager, "getGroupVolume", new Class<?>[]{int.class}, group);
            if (minimum < 0 || maximum < minimum || current < minimum || current > maximum) {
                throw new IllegalStateException("invalid volume range");
            }
            synchronized (lock) {
                if (!accepts(probeGeneration)) return;
                audioManager = manager;
                mediaGroupId = group;
                audioCapable = true;
                audioStatus = "실제 차량 미디어 그룹 제어 가능 · 페이더/밸런스 미지원";
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            boolean report;
            synchronized (lock) {
                report = accepts(probeGeneration);
                if (accepts(probeGeneration)) {
                    disableAudioLocked("차량 미디어 그룹 검증 실패");
                }
            }
            if (report) fault("차량 미디어 그룹 검증 실패: " + causeName(error));
        }
    }

    private void probeGear(Class<?> carClass, Object candidateCar, int probeGeneration) {
        String permission = stringField(carClass, "PERMISSION_POWERTRAIN",
                POWERTRAIN_PERMISSION_FALLBACK);
        if (!hasPermission(permission)) {
            synchronized (lock) {
                if (accepts(probeGeneration)) disableGearLocked("기어 읽기 권한 없음");
            }
            return;
        }
        try {
            String service = stringField(carClass, "PROPERTY_SERVICE", "property");
            Object manager = method(carClass, "getCarManager", String.class)
                    .invoke(candidateCar, service);
            if (manager == null) throw new IllegalStateException("property manager unavailable");

            Class<?> ids = Class.forName("android.car.VehiclePropertyIds");
            int propertyId = ids.getField("GEAR_SELECTION").getInt(null);
            Object config = method(manager.getClass(), "getCarPropertyConfig", int.class)
                    .invoke(manager, propertyId);
            if (config == null) {
                synchronized (lock) {
                    if (accepts(probeGeneration)) disableGearLocked("기어 속성이 차량에 노출되지 않음");
                }
                return;
            }

            Class<?> callbackType = Class.forName(
                    "android.car.hardware.property.CarPropertyManager$CarPropertyEventCallback");
            Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(),
                    new Class<?>[]{callbackType}, gearInvocationHandler(probeGeneration, propertyId));
            float rate = numberField(manager.getClass(), "SENSOR_RATE_ONCHANGE", 0.0f);
            boolean registered = (Boolean) method(manager.getClass(), "registerCallback",
                    callbackType, int.class, float.class)
                    .invoke(manager, callback, propertyId, rate);
            if (!registered) throw new IllegalStateException("gear callback rejected");

            synchronized (lock) {
                if (!accepts(probeGeneration)) {
                    unregisterGear(manager, callback, callbackType);
                    return;
                }
                propertyManager = manager;
                gearCallback = callback;
                gearReadable = true;
                parkingReadable = true;
                gearStatus = "실제 차량 기어 상태 연결됨";
            }
            Object initial = method(manager.getClass(), "getProperty", int.class, int.class)
                    .invoke(manager, propertyId, 0);
            handleGearValue(initial, probeGeneration);
        } catch (ReflectiveOperationException | RuntimeException error) {
            boolean report;
            synchronized (lock) {
                report = accepts(probeGeneration);
                if (accepts(probeGeneration)) disableGearLocked("기어 상태 검증 실패");
            }
            if (report) fault("기어 상태 검증 실패: " + causeName(error));
        }
    }

    private InvocationHandler gearInvocationHandler(int probeGeneration, int propertyId) {
        return (proxy, callbackMethod, args) -> {
            String name = callbackMethod.getName();
            if (name.equals("onChangeEvent") && args != null && args.length == 1) {
                handleGearValue(args[0], probeGeneration);
                return null;
            }
            if (name.equals("onErrorEvent")) {
                boolean report;
                synchronized (lock) {
                    report = accepts(probeGeneration);
                    if (accepts(probeGeneration)) disableGearLocked("기어 이벤트 오류");
                }
                if (report) fault("기어 이벤트 오류 (속성 " + propertyId + ")");
                return null;
            }
            if (name.equals("toString")) return "QuietDriveGearCallback";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == (args == null ? null : args[0]);
            return null;
        };
    }

    private void handleGearValue(Object propertyValue, int probeGeneration) {
        try {
            if (propertyValue == null) throw new IllegalStateException("empty gear value");
            Class<?> valueClass = propertyValue.getClass();
            int available = numberField(valueClass, "STATUS_AVAILABLE", 0).intValue();
            int status = (Integer) method(valueClass, "getStatus").invoke(propertyValue);
            Object raw = method(valueClass, "getValue").invoke(propertyValue);
            if (status != available || !(raw instanceof Integer)) {
                boolean report;
                synchronized (lock) {
                    report = accepts(probeGeneration);
                    if (accepts(probeGeneration)) disableGearLocked("기어 값 사용 불가");
                }
                if (report) fault("기어 값이 현재 사용 불가 상태임");
                return;
            }
            Class<?> gearClass = Class.forName("android.car.VehicleGear");
            int reverseValue = gearClass.getField("GEAR_REVERSE").getInt(null);
            int parkValue = gearClass.getField("GEAR_PARK").getInt(null);
            int gear = (Integer) raw;
            synchronized (lock) {
                if (!accepts(probeGeneration) || !gearReadable) return;
                boolean emulator = android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.MODEL.toLowerCase(java.util.Locale.ROOT).contains("emulator");
                gearStatus = (emulator ? "에뮬레이터 기어 " : "차량 기어 ") + (gear == reverseValue ? "R" : gear == parkValue ? "P" : "주행/중립");
            }
            mainHandler.post(() -> listener.onGear(gear == reverseValue, gear == parkValue));
        } catch (ReflectiveOperationException | RuntimeException error) {
            boolean report;
            synchronized (lock) {
                report = accepts(probeGeneration);
                if (accepts(probeGeneration)) disableGearLocked("기어 값 해석 실패");
            }
            if (report) fault("기어 값 해석 실패: " + causeName(error));
        }
    }

    private int readAudioIndex(String methodName) {
        Object manager;
        int group;
        synchronized (lock) {
            if (!canControlMedia()) return -1;
            manager = audioManager;
            group = mediaGroupId;
        }
        try {
            return invokeInt(manager, methodName, new Class<?>[]{int.class}, group);
        } catch (RuntimeException error) {
            synchronized (lock) {
                disableAudioLocked("차량 미디어 음량 읽기 실패");
            }
            fault("차량 미디어 음량 읽기 실패: " + causeName(error));
            return -1;
        }
    }

    private boolean accepts(int probeGeneration) {
        return !closed && generation == probeGeneration;
    }

    private boolean carConnectedLocked() {
        if (car == null) return false;
        try {
            return (Boolean) method(car.getClass(), "isConnected").invoke(car);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private void finishUnsupported(int probeGeneration, String reason) {
        synchronized (lock) {
            if (!accepts(probeGeneration)) return;
            disableAudioLocked(reason);
            disableGearLocked(reason);
        }
    }

    private void closeLocked() {
        Object manager = propertyManager;
        Object callback = gearCallback;
        if (manager != null && callback != null) {
            try {
                Class<?> callbackType = callback.getClass().getInterfaces()[0];
                unregisterGear(manager, callback, callbackType);
            } catch (RuntimeException ignored) {
                // The service may already be gone; disconnect below remains safe.
            }
        }
        disconnect(car);
        car = null;
        propertyManager = null;
        gearCallback = null;
        disableAudioLocked(audioStatus);
        disableGearLocked(gearStatus);
    }

    private static void unregisterGear(Object manager, Object callback, Class<?> callbackType) {
        try {
            method(manager.getClass(), "unregisterCallback", callbackType).invoke(manager, callback);
        } catch (ReflectiveOperationException ignored) {
            // Best effort during teardown.
        }
    }

    private static void disconnect(Object candidateCar) {
        if (candidateCar == null) return;
        try {
            method(candidateCar.getClass(), "disconnect").invoke(candidateCar);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Best effort during teardown.
        }
    }

    private void disableAudioLocked(String status) {
        audioCapable = false;
        audioManager = null;
        mediaGroupId = -1;
        audioStatus = status;
    }

    private void disableGearLocked(String status) {
        gearReadable = false;
        parkingReadable = false;
        gearStatus = status;
    }

    private void fault(String message) {
        mainHandler.post(() -> listener.onFault(message));
    }

    private boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String audioPermission() {
        return permissionField("PERMISSION_CAR_CONTROL_AUDIO_VOLUME", AUDIO_PERMISSION_FALLBACK);
    }

    private String powertrainPermission() {
        return permissionField("PERMISSION_POWERTRAIN", POWERTRAIN_PERMISSION_FALLBACK);
    }

    private String permissionField(String field, String fallback) {
        try {
            return stringField(Class.forName("android.car.Car"), field, fallback);
        } catch (ClassNotFoundException ignored) {
            return fallback;
        }
    }

    private static boolean isMediaOnly(int[] usages) {
        if (usages == null || usages.length == 0) return false;
        for (int usage : usages) {
            if (usage != AudioAttributes.USAGE_MEDIA && usage != AudioAttributes.USAGE_GAME) {
                return false;
            }
        }
        return true;
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        return owner.getMethod(name, parameters);
    }

    private static int invokeInt(Object target, String name, Class<?>[] types, Object... args) {
        try {
            return ((Number) method(target.getClass(), name, types).invoke(target, args)).intValue();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String stringField(Class<?> owner, String name, String fallback) {
        try {
            Field field = owner.getField(name);
            Object value = field.get(null);
            return value instanceof String ? (String) value : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static <T extends Number> T numberField(Class<?> owner, String name, T fallback) {
        try {
            Object value = owner.getField(name).get(null);
            @SuppressWarnings("unchecked") T number = (T) value;
            return number;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static String causeName(Throwable error) {
        Throwable cause = error;
        if (error instanceof InvocationTargetException
                && ((InvocationTargetException) error).getTargetException() != null) {
            cause = ((InvocationTargetException) error).getTargetException();
        } else if (error.getCause() != null) {
            cause = error.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : " · " + message);
    }
}
