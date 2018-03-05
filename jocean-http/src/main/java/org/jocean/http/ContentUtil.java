package org.jocean.http;

import java.io.OutputStream;

import javax.ws.rs.core.MediaType;

import rx.functions.Action2;

public class ContentUtil {
    
    private ContentUtil() {
        throw new IllegalStateException("No instances!");
    }
    
    private final static Action2<Object, OutputStream> _ASXML = new Action2<Object, OutputStream>() {
        @Override
        public void call(final Object bean, final OutputStream os) {
            MessageUtil.serializeToXml(bean, os);
        }};
    
    private final static Action2<Object, OutputStream> _ASJSON = new Action2<Object, OutputStream>() {
        @Override
        public void call(final Object bean, final OutputStream os) {
            MessageUtil.serializeToJson(bean, os);
        }};
        
    public static final ContentEncoder TOXML = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_XML;
        }

        @Override
        public Action2<Object, OutputStream> encoder() {
            return _ASXML;
        }};
    public static final ContentEncoder TOJSON = new ContentEncoder() {
        @Override
        public String contentType() {
            return MediaType.APPLICATION_JSON;
        }
        @Override
        public Action2<Object, OutputStream> encoder() {
            return _ASJSON;
        }};
}
