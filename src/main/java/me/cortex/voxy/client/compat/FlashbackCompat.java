package me.cortex.voxy.client.compat;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ForgePlatform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;

public class FlashbackCompat {
    public static final boolean FLASHBACK_INSTALLED = ForgePlatform.isModLoaded("flashback");

    public static Path getReplayStoragePath() {
        if (!FLASHBACK_INSTALLED) {
            return null;
        }
        return getReplayStoragePath0();
    }

    private static Path getReplayStoragePath0() {
        try {
            Class<?> flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
            Method getReplayServer = flashbackClass.getMethod("getReplayServer");
            Object replayServer = getReplayServer.invoke(null);
            if (replayServer == null) {
                return null;
            }

            Object meta = replayServer.getClass().getMethod("getMetadata").invoke(replayServer);
            if (meta != null) {
                var path = ((IFlashbackMeta) meta).getVoxyPath();
                if (path != null) {
                    Logger.info("Flashback replay server exists and meta exists");
                    if (path.exists()) {
                        Logger.info("Flashback voxy path exists in filesystem, using this as lod data source");
                        return path.toPath();
                    }
                    Logger.warn("Flashback meta had voxy path saved but path doesnt exist");
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            Logger.warn("Flashback compatibility could not inspect replay metadata", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            Logger.warn("Flashback replay metadata lookup threw an exception", cause);
        } catch (ClassCastException exception) {
            Logger.warn("Flashback metadata did not implement IFlashbackMeta", exception);
        }
        return null;
    }
}
