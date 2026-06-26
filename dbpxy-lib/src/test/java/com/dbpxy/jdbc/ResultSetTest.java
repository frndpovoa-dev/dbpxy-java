package com.dbpxy.jdbc;

import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class ResultSetTest {

    @Test
    void testGetString_NonStringValue() {
        assertDoesNotThrow(() -> StringValue.parseFrom(Int32Value.newBuilder().setValue(1).build().toByteString()).getValue());
    }
}
