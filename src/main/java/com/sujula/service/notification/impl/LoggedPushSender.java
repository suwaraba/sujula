package com.sujula.service.notification.impl;

import java.util.List;

import org.springframework.stereotype.Component;

import com.sujula.model.notification.PushDevice;
import com.sujula.service.notification.PushSender;

import lombok.extern.slf4j.Slf4j;

/**
 * What happens to a push notification when no provider is configured.
 *
 * <p>It is recorded and it does not pretend. The device registry, the preference
 * matrix and the resolution of who <em>would</em> be sent to are all real and
 * are exercised exactly as they would be with a provider behind them — which is
 * the point of putting the seam here rather than short-circuiting earlier: the
 * code that decides who gets told is the code that runs in production.
 *
 * <p>It reports {@code isConfigured() == false} rather than claiming success, so
 * an operator reading a notification's {@code sentOn} column sees the truth.
 *
 * <p>The log line names the device and not the token. A token in a log file is
 * an address anybody with the log can send that handset a message from.
 */
@Slf4j
@Component
public class LoggedPushSender implements PushSender {

    @Override
    public int send(List<PushDevice> devices, String title, String body, String referenceId) {
        if (devices == null || devices.isEmpty()) {
            return 0;
        }
        for (PushDevice device : devices) {
            log.info("[Push] (no provider configured) would send \"{}\" to device {} ({} — {})",
                    title, device.getId(), device.getPlatform(), device.getLabel());
        }
        return 0;
    }

    @Override
    public boolean isConfigured() {
        return false;
    }
}
