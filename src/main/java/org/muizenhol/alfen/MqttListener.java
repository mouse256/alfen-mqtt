package org.muizenhol.alfen;

import io.smallrye.mutiny.tuples.Tuple2;
import io.smallrye.reactive.messaging.mqtt.ReceivingMqttMessageMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class MqttListener {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
    private final List<Tuple2<Pattern, Listener>> listeners = new ArrayList<>();

    public interface Listener {
        void handleMessage(String topic, Matcher matchedTopic, String payload);
    }


    @Incoming("set")
    public CompletionStage<Void> handleMsg(Message<String> setMsg) {
        StreamSupport.stream(setMsg.getMetadata().spliterator(), false)
                .filter(x -> x instanceof ReceivingMqttMessageMetadata)
                .map(x -> (ReceivingMqttMessageMetadata) x)
                .findFirst()
                .ifPresentOrElse(meta -> handleMsgNoReturn(setMsg, meta), () -> {
                    LOG.warn("Can't find MQTT message metadata");
                });

        return setMsg.ack();
    }

    public void register(Pattern topicPattern, Listener listener) {
        listeners.add(Tuple2.of(topicPattern, listener));
    }

    private void handleMsgNoReturn(Message<String> setMsg, ReceivingMqttMessageMetadata meta) {
        listeners.forEach(t -> {
            Matcher m = t.getItem1().matcher(meta.getTopic());
            if (m.matches()) {
                t.getItem2().handleMessage(meta.getTopic(), m, setMsg.getPayload());
            }
        });
    }
}