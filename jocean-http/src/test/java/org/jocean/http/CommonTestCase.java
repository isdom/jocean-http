package org.jocean.http;

import static org.junit.Assert.*;

import org.junit.Test;

public class CommonTestCase {

    private static class Func1 {
        public void func1(final Object ... objs) {
            this._objs = objs;
        }
        
        Object[] _objs;
    }
    
    @Test
    public void testVarargs() {
        final Func1 func1 = new Func1();
        
        func1.func1();
        assertEquals(0, func1._objs.length);
    }

}
