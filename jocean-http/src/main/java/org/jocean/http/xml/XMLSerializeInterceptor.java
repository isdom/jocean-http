package org.jocean.http.xml;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;

import javax.xml.namespace.NamespaceContext;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jocean.http.ContentEncoder.EncodeAware;

public class XMLSerializeInterceptor implements XMLStreamWriter {

    private final EncodeAware _encodeAware;
    private final Deque<String> _elements = new ArrayDeque<>(3);

    public XMLSerializeInterceptor(final EncodeAware encodeAware) {
        this._encodeAware = encodeAware;
    }

    private String fullname() {
        final Iterator<String> it = this._elements.iterator();
        if (! it.hasNext())
            return "";

        final StringBuilder sb = new StringBuilder();
        for (;;) {
            sb.append(it.next());
            if (! it.hasNext())
                return sb.toString();
            sb.append('.');
        }
    }

    @Override
    public void writeStartElement(final String localName) throws XMLStreamException {
        this._elements.addLast(localName);
    }

    @Override
    public void writeStartElement(final String namespaceURI, final String localName) throws XMLStreamException {
        this._elements.addLast(localName);
    }

    @Override
    public void writeStartElement(final String prefix, final String localName, final String namespaceURI) throws XMLStreamException {
        this._elements.addLast(localName);
    }

    @Override
    public void writeEmptyElement(final String namespaceURI, final String localName) throws XMLStreamException {
        this._encodeAware.onPropertyEncode(null, fullname() + "." + localName, null);
    }

    @Override
    public void writeEmptyElement(final String prefix, final String localName, final String namespaceURI) throws XMLStreamException {
        this._encodeAware.onPropertyEncode(null, fullname() + "." + localName, null);
    }

    @Override
    public void writeEmptyElement(final String localName) throws XMLStreamException {
        this._encodeAware.onPropertyEncode(null, fullname() + "." + localName, null);
    }

    @Override
    public void writeEndElement() throws XMLStreamException {
        this._elements.removeLast();
    }

    @Override
    public void writeEndDocument() throws XMLStreamException {
    }

    @Override
    public void close() throws XMLStreamException {
    }

    @Override
    public void flush() throws XMLStreamException {
    }

    @Override
    public void writeAttribute(final String localName, final String value) throws XMLStreamException {
    }

    @Override
    public void writeAttribute(final String prefix, final String namespaceURI, final String localName, final String value)
            throws XMLStreamException {
    }

    @Override
    public void writeAttribute(final String namespaceURI, final String localName, final String value) throws XMLStreamException {
    }

    @Override
    public void writeNamespace(final String prefix, final String namespaceURI) throws XMLStreamException {
    }

    @Override
    public void writeDefaultNamespace(final String namespaceURI) throws XMLStreamException {
    }

    @Override
    public void writeComment(final String data) throws XMLStreamException {
    }

    @Override
    public void writeProcessingInstruction(final String target) throws XMLStreamException {
    }

    @Override
    public void writeProcessingInstruction(final String target, final String data) throws XMLStreamException {
    }

    @Override
    public void writeCData(final String data) throws XMLStreamException {
        this._encodeAware.onPropertyEncode(null, fullname(), data);
    }

    @Override
    public void writeDTD(final String dtd) throws XMLStreamException {
    }

    @Override
    public void writeEntityRef(final String name) throws XMLStreamException {
    }

    @Override
    public void writeStartDocument() throws XMLStreamException {
    }

    @Override
    public void writeStartDocument(final String version) throws XMLStreamException {
    }

    @Override
    public void writeStartDocument(final String encoding, final String version) throws XMLStreamException {
    }

    @Override
    public void writeCharacters(final String text) throws XMLStreamException {
        this._encodeAware.onPropertyEncode(null, fullname(), text);
    }

    @Override
    public void writeCharacters(final char[] text, final int start, final int len) throws XMLStreamException {
        this._encodeAware.onPropertyEncode(null, fullname(), new String(text, start, len));
    }

    @Override
    public String getPrefix(final String uri) throws XMLStreamException {
        return null;
    }

    @Override
    public void setPrefix(final String prefix, final String uri) throws XMLStreamException {
    }

    @Override
    public void setDefaultNamespace(final String uri) throws XMLStreamException {
    }

    @Override
    public void setNamespaceContext(final NamespaceContext context) throws XMLStreamException {
    }

    @Override
    public NamespaceContext getNamespaceContext() {
        return null;
    }

    @Override
    public Object getProperty(final String name) throws IllegalArgumentException {
        return null;
    }
}
