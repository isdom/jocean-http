package org.jocean.http;

import java.lang.reflect.ParameterizedType;

public class TestGenericClass {

    public interface IA<T> {};
    
    public static class A<T> implements IA<T> {};
    public static class B extends A<String> {};
    
    public static void main(String[] args) {
        
        B b = new B();
        System.out.println(((ParameterizedType)b.getClass().getGenericSuperclass()).getActualTypeArguments()[0]);
    }

}
