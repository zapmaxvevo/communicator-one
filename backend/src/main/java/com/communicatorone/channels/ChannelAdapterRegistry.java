package com.communicatorone.channels;

import com.communicatorone.messaging.ChannelType;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
class ChannelAdapterRegistry {

    private final Map<ChannelType, ChannelAdapter> adaptersByType;

    ChannelAdapterRegistry(List<ChannelAdapter> adapters) {
        this.adaptersByType = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(ChannelAdapter::type, Function.identity(),
                        ChannelAdapterRegistry::rejectSecondAdapter));
    }

    ChannelAdapter adapterFor(ChannelType type) {
        var adapter = adaptersByType.get(type);
        if (adapter == null) {
            throw new UnsupportedChannelException(type);
        }
        return adapter;
    }

    private static ChannelAdapter rejectSecondAdapter(ChannelAdapter first, ChannelAdapter second) {
        throw new IllegalStateException("Channel type " + first.type() + " has two adapters: "
                + first.getClass().getSimpleName() + " and " + second.getClass().getSimpleName());
    }
}
