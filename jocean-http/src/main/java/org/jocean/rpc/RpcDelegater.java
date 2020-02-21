/**
 *
 */
package org.jocean.rpc;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import org.jocean.http.ContentDecoder;
import org.jocean.http.ContentEncoder;
import org.jocean.http.ContentUtil;
import org.jocean.http.Interact;
import org.jocean.http.MessageBody;
import org.jocean.http.RpcRunner;
import org.jocean.idiom.Pair;
import org.jocean.rpc.annotation.ConstParams;
import org.jocean.rpc.annotation.ResponseType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpMethod;
import rx.Observable;
import rx.Observable.Transformer;

/**
 * @author isdom
 *
 */
public class RpcDelegater {
    private static final Logger LOG = LoggerFactory.getLogger(RpcDelegater.class);

    static final ContentEncoder[] MIME_ENCODERS = new ContentEncoder[]{
            ContentUtil.TOJSON, ContentUtil.TOXML, ContentUtil.TOTEXT, ContentUtil.TOHTML};

    static final ContentDecoder[] MIME_DECODERS = new ContentDecoder[]{ContentUtil.ASJSON, ContentUtil.ASXML, ContentUtil.ASTEXT};

    @SuppressWarnings("unchecked")
    public static <RPC> RPC build(final Class<RPC> rpcType) {
        return (RPC) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { rpcType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (null == args || args.length == 0) {
                            return delegate(rpcType, method.getReturnType(), "rpc." + rpcType.getSimpleName() + "." + method.getName());
                        } else {
                            return null;
                        }
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static <T, R> T delegate(
            final Class<?> apiType,
            final Class<T> builderType,
            final String opname) {
        final Map<String, Object> params = new HashMap<>();

        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { builderType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (null != args && args.length == 1) {
                            final QueryParam queryParam = method.getAnnotation(QueryParam.class);
                            if (null != queryParam) {
                                params.put(queryParam.value(), args[0]);
                            }
                            return proxy;
                        } else if (null == args || args.length == 0) {
                            addConstParams(apiType, params);
                            addConstParams(method, params);

                            return (Transformer<RpcRunner, R>) runners -> runners.flatMap(runner -> runner.name(opname).submit(
                                    callapi(apiType, method, params, null, null)));
                        }

                        return null;
                    }
                });
    }

    @SuppressWarnings("unchecked")
    public static <RPC> RPC build2(final Class<RPC> rpcType) {
        return (RPC) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { rpcType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (null == args || args.length == 0) {
                            return delegate2(rpcType, method.getReturnType());
                        } else {
                            return null;
                        }
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static <T, R> T delegate2(
            final Class<?> apiType,
            final Class<T> builderType) {
        final Map<String, Object> params = new HashMap<>();
        final AtomicReference<Observable<? extends MessageBody>> getbodyRef = new AtomicReference<>(null);
        final AtomicReference<Pair<Object, ContentEncoder>> contentRef = new AtomicReference<>(null);

        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { builderType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (null != args && args.length == 1) {
                            final QueryParam queryParam = method.getAnnotation(QueryParam.class);
                            final Produces produces = method.getAnnotation(Produces.class);
                            if (null != queryParam) {
                                params.put(queryParam.value(), args[0]);
                            } else if (null != produces) {
                                final ContentEncoder bodyEncoder = ContentUtil.selectCodec(produces.value(), MIME_ENCODERS);
                                if (null != bodyEncoder) {
                                    contentRef.set(Pair.of(args[0], bodyEncoder));
                                }
                            } else {
                                final Class<?> arg1stType = method.getParameterTypes()[0];

                                if (MessageBody.class.isAssignableFrom(arg1stType)) {
                                    // means: API body(final MessageBody body); not care method name
                                    getbodyRef.set(Observable.just((MessageBody)args[0]));
                                } else if (arg1stType.equals(Observable.class)) {
                                    final Type arg1stGenericType = method.getGenericParameterTypes()[0];
                                    if ( arg1stGenericType instanceof ParameterizedType ) {
                                        final Type actualGenericType = ((ParameterizedType)arg1stGenericType).getActualTypeArguments()[0];
                                        if (actualGenericType instanceof Class
                                            && MessageBody.class.isAssignableFrom((Class<?>)actualGenericType)) {
                                            // Observable<MessageBody> or Observable<XXXMessageBody>
                                            getbodyRef.set((Observable<? extends MessageBody>)args[0]);
                                        }
                                        // TODO
//                                        else if (actualGenericType instanceof WildcardType) {
//                                        }
                                    }
                                }
                            }
                            return proxy;
                        } else if (null == args || args.length == 0) {
                            addConstParams(apiType, params);
                            addConstParams(method, params);

                            return callapi(apiType, method, params, getbodyRef.get(), contentRef.get());
                        }

                        return null;
                    }
                });
    }

    private static <R> Transformer<Interact, R> callapi(
            final Class<?> api,
            final Method method,
            final Map<String, Object> params,
            final Observable<? extends MessageBody> getbody,
            final Pair<Object, ContentEncoder> getcontent) {
        return interacts -> interacts.flatMap(interact -> {
                    Interact newInteract = assignUriAndPath(method, interact);
                    if (null != newInteract) {
                        interact = newInteract;
                    } else {
                        newInteract = assignUriAndPath(api, interact);
                        if (null != newInteract) {
                            interact = newInteract;
                        }
                    }

                    interact = interact.method(getHttpMethod(method));

                    for (final Map.Entry<String, Object> entry : params.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            interact = interact.paramAsQuery(entry.getKey(), entry.getValue().toString());
                        }
                    }
                    if (null != getbody) {
                        interact = interact.body(getbody);
                    } else if ( null != getcontent) {
                        interact = interact.body(getcontent.getFirst(), getcontent.getSecond());
                    }

                    ContentDecoder contentDecoder = ContentUtil.ASJSON;

                    final Consumes consumes = method.getAnnotation(Consumes.class);
                    if (null != consumes) {
                        final ContentDecoder selected = ContentUtil.selectCodec(consumes.value(), MIME_DECODERS);
                        if (null != selected) {
                            contentDecoder = selected;
                        }
                    }

                    final ResponseType responseType = method.getAnnotation(ResponseType.class);
                    if (null != responseType) {
                        return interact.responseAs(contentDecoder, (Class<R>)responseType.value());
                    } else {
                        return Observable.error(new RuntimeException("Unknown Response Type"));
                    }
                });
    }

    private static HttpMethod getHttpMethod(final Method method) {
        if (null != method.getAnnotation(GET.class)) {
            return HttpMethod.GET;
        } else if (null != method.getAnnotation(POST.class)) {
            return HttpMethod.POST;
        } else if (null != method.getAnnotation(PUT.class)) {
            return HttpMethod.PUT;
        } else if (null != method.getAnnotation(DELETE.class)) {
            return HttpMethod.DELETE;
        } else if (null != method.getAnnotation(HEAD.class)) {
            return HttpMethod.HEAD;
        } else if (null != method.getAnnotation(OPTIONS.class)) {
            return HttpMethod.OPTIONS;
        } else if (null != method.getAnnotation(PATCH.class)) {
            return HttpMethod.PATCH;
        } else {
            return HttpMethod.GET;
        }
    }

    private static void addConstParams(final AnnotatedElement annotatedElement, final Map<String, Object> params) {
        if (null == annotatedElement) {
            return;
        }
        final ConstParams constParams = annotatedElement.getAnnotation(ConstParams.class);
        // add const params mark by XXXBuilder.call method
        if (null != constParams) {
            final String keyValues[] = constParams.value();
            for (int i = 0; i < keyValues.length-1; i+=2) {
                params.put(keyValues[i], keyValues[i+1]);
            }
        }
    }

    private static Interact assignUriAndPath(final AnnotatedElement annotatedElement, final Interact interact) {
        if (null == annotatedElement) {
            return null;
        }
        final Path path = annotatedElement.getAnnotation(Path.class);
        if (null != path) {
            try {
                final URI uri = new URI(path.value());
                final String colonWithPort = uri.getPort() > 0 ? ":" + uri.getPort() : "";
                LOG.info("uri-- {}://{}{}{}", uri.getScheme(), uri.getHost(), colonWithPort, uri.getPath());
                return interact.uri(uri.getScheme() + "://" + uri.getHost() + colonWithPort).path(uri.getPath());
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }
}
