package com.bugbytz.prolink;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.deepsymmetry.beatlink.CdjStatus;
import org.deepsymmetry.beatlink.VirtualCdj;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

public class LoadCommandConsumer {
    private final Consumer<String, String> consumer;
    private final ObjectMapper objectMapper;

    private volatile boolean running = true;

    public void shutdown() {
        running = false;
    }

    public LoadCommandConsumer(String bootstrapServers, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        this.consumer = new KafkaConsumer<>(props);
        this.objectMapper = new ObjectMapper();
    }

    public void startConsuming() {
        consumer.subscribe(Collections.singletonList("load"));

        try {
            while (running) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                for (ConsumerRecord<String, String> record : records) {
                    processMessage(record.value());
                }
            }
        } finally {
            consumer.close();
        }
    }

    private void processMessage(String message) {
        try {
            LoadRequest request = objectMapper.readValue(message, LoadRequest.class);
            VirtualCdj.getInstance().sendLoadTrackCommand(
                    request.getTargetPlayer(),
                    request.getRekordboxId(),
                    request.getSourcePlayer(),
                    CdjStatus.TrackSourceSlot.USB_SLOT,
                    CdjStatus.TrackType.REKORDBOX
            );
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        LoadCommandConsumer consumer = new LoadCommandConsumer("localhost:9092", "load-command-group");
        consumer.startConsuming();
    }
}
