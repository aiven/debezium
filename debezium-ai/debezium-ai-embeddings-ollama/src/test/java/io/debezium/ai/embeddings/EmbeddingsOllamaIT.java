/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ai.embeddings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.assertj.core.data.Offset;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integrations tests for {@link FieldToEmbedding} SMT which uses {@link OllamaModelFactory}.
 *
 * @author vjuranek
 */
public class EmbeddingsOllamaIT {
    private static final String OLLAMA_IMAGE_NAME = "mirror.gcr.io/ollama/ollama:0.6.2";
    private static final String OLLAMA_TEST_MODEL = "all-minilm";

    private static final OllamaContainer ollama = new OllamaContainer(
            DockerImageName.parse(OLLAMA_IMAGE_NAME).asCompatibleSubstituteFor("ollama/ollama"))
            .withStartupTimeout(Duration.ofSeconds(180));

    private final FieldToEmbedding<SourceRecord> embeddingSmt = new FieldToEmbedding();

    @BeforeClass
    public static void startDatabase() {
        ollama.start();
    }

    @AfterClass
    public static void stopDatabase() {
        ollama.stop();
    }

    @Test
    public void testOllamaEmbeddings() throws InterruptedException, IOException {
        ollama.execInContainer("ollama", "pull", OLLAMA_TEST_MODEL);

        embeddingSmt.configure(Map.of(
                "embeddings.field.source", "after.product",
                "embeddings.field.embedding", "after.prod_embedding",
                "embeddings.ollama.url", ollama.getEndpoint(),
                "embeddings.ollama.model.name", OLLAMA_TEST_MODEL));
        SourceRecord transformedRecord = embeddingSmt.apply(FieldToEmbeddingTest.SOURCE_RECORD);

        Struct payloadStruct = (Struct) transformedRecord.value();
        assertThat(payloadStruct.getStruct("after").getString("product")).contains("a product");
        List<Float> embeddings = payloadStruct.getStruct("after").getArray("prod_embedding");
        assertThat(embeddings.size()).isEqualTo(384);

        final Offset<Float> offset = Offset.offset(0.001f);
        assertThat(embeddings.get(0)).isCloseTo(-0.07157089f, offset);
        assertThat(embeddings.get(1)).isCloseTo(0.022460647f, offset);
        assertThat(embeddings.get(2)).isCloseTo(-0.02369636f, offset);
    }
}
