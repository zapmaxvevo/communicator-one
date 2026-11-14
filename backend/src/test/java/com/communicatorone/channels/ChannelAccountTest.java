package com.communicatorone.channels;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.communicatorone.messaging.ChannelType;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChannelAccountTest {

    private static final String MISSING_TYPE = "A channel account needs its channel type";
    private static final String MISSING_EXTERNAL_ID = "A channel account needs the id its provider gave it";
    private static final String MISSING_DISPLAY_NAME = "A channel account needs a display name";

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteRegistrations")
    void channelAccountWithoutTypeExternalIdOrDisplayNameIsRejected(String missing, ChannelType channelType,
            String externalAccountId, String displayName, String expectedMessage) {
        // When
        var rejection = catchThrowable(
                () -> ChannelAccount.register(channelType, externalAccountId, displayName, true));

        // Then
        assertThat(rejection).isInstanceOf(IllegalArgumentException.class).hasMessage(expectedMessage);
    }

    static Stream<Arguments> incompleteRegistrations() {
        return Stream.of(
                Arguments.of("null channel type", null, "100200300", "Ventas", MISSING_TYPE),
                Arguments.of("null external id", ChannelType.WHATSAPP, null, "Ventas", MISSING_EXTERNAL_ID),
                Arguments.of("empty external id", ChannelType.WHATSAPP, "", "Ventas", MISSING_EXTERNAL_ID),
                Arguments.of("blank external id", ChannelType.WHATSAPP, "   ", "Ventas", MISSING_EXTERNAL_ID),
                Arguments.of("null display name", ChannelType.WHATSAPP, "100200300", null, MISSING_DISPLAY_NAME),
                Arguments.of("empty display name", ChannelType.WHATSAPP, "100200300", "", MISSING_DISPLAY_NAME),
                Arguments.of("blank display name", ChannelType.WHATSAPP, "100200300", "   ",
                        MISSING_DISPLAY_NAME));
    }
}
