package org.foo.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MyHelperTest {
    
    @Test
    public void testMyHelper() {
        assertEquals("Hello world!", MyHelper.sayHello("world"));
    }
}
