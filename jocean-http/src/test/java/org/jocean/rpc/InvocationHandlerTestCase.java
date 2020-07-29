package org.jocean.rpc;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class InvocationHandlerTestCase {

    interface Super<T> {
        T func1();
    }

    interface Derived extends Super<Derived> {
    }

    @Test
    public final void testIsAssignableFrom() {
        final AtomicReference<Class<?>> ref = new AtomicReference<>();
        final Derived derived = (Derived)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { Derived.class },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
                        ref.set(method.getReturnType());
                        return null;
                    }});
        derived.func1();

        System.out.println(ref.get());
        assertTrue(Object.class.isAssignableFrom(ref.get()));
    }

}
