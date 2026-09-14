package com.sujula.dto.request.notification;

import java.util.List;

import com.sujula.model.constant.NotificationChannel;
import com.sujula.model.constant.NotificationEvent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a user sends about their own notifications. */
public final class NotificationRequests {

    private NotificationRequests() {}

    /**
     * A set of switches being changed.
     *
     * <p>A list of changes rather than the whole matrix. Sending the matrix back
     * would mean a client with a stale copy silently reverting a switch the user
     * changed on another device, and there are two hundred of them.
     */
    public record UpdatePreferences(
            @NotEmpty(message = "Say which settings to change")
            @Size(max = 200) @Valid List<Switch> changes) {}

    public record Switch(
            @NotNull(message = "Which kind of notification") NotificationEvent event,
            @NotNull(message = "Which channel") NotificationChannel channel,
            @NotNull(message = "On or off") Boolean enabled) {}

    /**
     * A handset asking to be told things.
     *
     * <p>The app sends this on every start, with the same token, which is why
     * registering twice is a refresh rather than a second device.
     */
    public record RegisterDevice(
            @NotBlank(message = "The device token is required")
            @Size(max = 512) String token,

            @NotBlank(message = "Say which kind of device")
            @Pattern(regexp = "^(?i)(ANDROID|IOS|WEB)$",
                     message = "A device is ANDROID, IOS or WEB")
            String platform,

            /** What the user will see in their list of devices. */
            @Size(max = 120) String label) {}
}
