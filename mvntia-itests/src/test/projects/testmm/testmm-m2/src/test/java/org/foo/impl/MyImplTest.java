package org.foo.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MyImplTest {
    
    @Test
    public void testMyHelper() {
        assertEquals("Hello world!", new MyImpl().greet("world"));
    }
}
