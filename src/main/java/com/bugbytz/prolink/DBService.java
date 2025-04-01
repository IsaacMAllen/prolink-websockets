package com.bugbytz.prolink;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.deepsymmetry.beatlink.data.DatabaseListener;
import org.deepsymmetry.beatlink.data.SlotReference;
import org.deepsymmetry.cratedigger.Database;
import org.deepsymmetry.cratedigger.pdb.RekordboxPdb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

public class DBService implements DatabaseListener {
    @Override
    public void databaseMounted(SlotReference slot, Database database) {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "com.bugbytz.prolink.CustomSerializer");

        try (Producer<String, Track> producer = new KafkaProducer<>(props)) {
            Map<Long, String> artists = new HashMap<>();
            database.artistIndex.forEach((id, artistRow) ->
                    artists.put(id, extractText(artistRow.name())));

            Map<Long, String> keys = new HashMap<>();
            database.musicalKeyIndex.forEach((id, keyRow) ->
                    keys.put(id, extractText(keyRow.name())));

            List<Future<RecordMetadata>> futures = new ArrayList<>();

            database.trackIndex.forEach((id, trackRow) -> {
                Track track = new Track(
                        id,
                        extractText(trackRow.title()),
                        trackRow.tempo() / 100.0,
                        trackRow.rating(),
                        trackRow.artworkId(),
                        keys.get(trackRow.keyId()),
                        artists.get(trackRow.artistId()),
                        trackRow.artistId(),
                        trackRow.duration() / 60,
                        trackRow.duration() % 60,
                        slot.player
                );
                //App.tracks.add(track);
                ProducerRecord<String, Track> record = new ProducerRecord<>("library", "track-" + track.getId(), track);
                futures.add(producer.send(record));
            });

            // Wait for all sends to complete
            for (Future<RecordMetadata> future : futures) {
                future.get();
            }

            producer.flush();
        } catch (Exception e) {
            e.printStackTrace();
            // Handle exception appropriately
        }

        //App.dbRead = true;
    }


    private String extractText(RekordboxPdb.DeviceSqlString sqlString) {
        if (sqlString.body() instanceof RekordboxPdb.DeviceSqlShortAscii) {
            return ((RekordboxPdb.DeviceSqlShortAscii) sqlString.body()).text();
        } else if (sqlString.body() instanceof RekordboxPdb.DeviceSqlLongUtf16le) {
            return ((RekordboxPdb.DeviceSqlLongUtf16le) sqlString.body()).text();
        } else {
            return "Unknown SQL string type";
        }
    }

    @Override
    public void databaseUnmounted(SlotReference slot, Database database) {
        System.out.println("");
    }
}
