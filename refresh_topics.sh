#!/bin/bash
kafka-topics --bootstrap-server localhost:9092 \
  --delete \
  --topic load
kafka-topics --bootstrap-server localhost:9092 \
  --delete \
  --topic device-status
kafka-topics --bootstrap-server localhost:9092 \
  --delete \
  --topic library
kafka-topics --create --topic library --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --config retention.ms=100
kafka-topics --create --topic device-status --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --config retention.ms=100
kafka-topics --create --topic load --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1 --config retention.ms=100
