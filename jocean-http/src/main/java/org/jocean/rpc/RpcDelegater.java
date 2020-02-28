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
import javax.ws.rs.HeaderParam;
import javax.ws.rs.OPTIONS;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import org.jocean.http.ContentDecoder;
import org.jocean.http.ContentEncoder;
import org.jocean.http.ContentUtil;
import org.jocean.http.FullMessage;
import org.jocean.http.Interact;
import org.jocean.http.MessageBody;
import org.jocean.http.RpcRunner;
import org.jocean.idiom.Pair;
import org.jocean.rpc.annotation.ConstParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
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
        final Map<String, Object> queryParams = new HashMap<>();
        final Map<String, Object> pathParams = new HashMap<>();
        final Map<String, Object> headerParams = new HashMap<>();

        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { builderType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (null != args && args.length == 1) {
                            final QueryParam queryParam = method.getAnnotation(QueryParam.class);
                            final PathParam pathParam = method.getAnnotation(PathParam.class);
                            final HeaderParam headerParam = method.getAnnotation(HeaderParam.class);
                            if (null != queryParam) {
                                queryParams.put(queryParam.value(), args[0]);
                            } else if (null != pathParam) {
                                pathParams.put(pathParam.value(), args[0]);
                            } else if (null != headerParam) {
                                headerParams.put(headerParam.value(), args[0]);
                            }
                            return proxy;
                        } else if (null == args || args.length == 0) {
                            addConstParams(apiType, queryParams);
                            addConstParams(method, queryParams);

                            return (Transformer<RpcRunner, Object>) runners -> runners.flatMap(runner -> runner.name(opname).submit(
                                    callapi(apiType, builderType, method, queryParams, pathParams, headerParams, null, null)));
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
        final Map<String, Object> queryParams = new HashMap<>();
        final Map<String, Object> pathParams = new HashMap<>();
        final Map<String, Object> headerParams = new HashMap<>();
        final AtomicReference<Observable<? extends MessageBody>> getbodyRef = new AtomicReference<>(null);
        final AtomicReference<Pair<Object, ContentEncoder>> contentRef = new AtomicReference<>(null);

        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] { builderType },
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (null != args && args.length == 1) {
                            final QueryParam queryParam = method.getAnnotation(QueryParam.class);
                            final PathParam pathParam = method.getAnnotation(PathParam.class);
                            final HeaderParam headerParam = method.getAnnotation(HeaderParam.class);
                            final Produces produces = method.getAnnotation(Produces.class);
                            if (null != queryParam) {
                                queryParams.put(queryParam.value(), args[0]);
                            } else if (null != pathParam) {
                                pathParams.put(pathParam.value(), args[0]);
                            } else if (null != headerParam) {
                                headerParams.put(headerParam.value(), args[0]);
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
                            addConstParams(apiType, queryParams);
                            addConstParams(method, queryParams);

                            return callapi(apiType, builderType, method, queryParams, pathParams, headerParams, getbodyRef.get(), contentRef.get());
                        }

                        return null;
                    }
                });
    }

    private static Transformer<Interact, ? extends Object> callapi(
            final Class<?> api,
            final Class<?> builder,
            final Method method,
            final Map<String, Object> queryParams,
            final Map<String, Object> pathParams,
            final Map<String, Object> headerParams,
            final Observable<? extends MessageBody> getbody,
            final Pair<Object, ContentEncoder> getcontent) {
        return interacts -> interacts.flatMap(interact -> {
                    Interact newInteract = assignUriAndPath(method, pathParams, interact);
                    if (null != newInteract) {
                        interact = newInteract;
                    } else {
                        newInteract = assignUriAndPath(api, pathParams, interact);
                        if (null != newInteract) {
                            interact = newInteract;
                        }
                    }

                    interact = interact.method(getHttpMethod(method));

                    for (final Map.Entry<String, Object> entry : queryParams.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            interact = interact.paramAsQuery(entry.getKey(), entry.getValue().toString());
                        }
                    }

                    if (!headerParams.isEmpty()) {
                        // set headers
                        interact = interact.onrequest(obj -> {
                            if (obj instanceof HttpRequest) {
                                for (final Map.Entry<String, Object> entry : headerParams.entrySet()) {
                                    if (entry.getKey() != null && entry.getValue() != null) {
                                        ((HttpRequest)obj).headers().set(entry.getKey(), entry.getValue());
                                    }
                                }
                            }
                        });
                    }

                    if (null != getbody) {
                        interact = interact.body(getbody);
                    } else if ( null != getcontent) {
                        interact = interact.body(getcontent.getFirst(), getcontent.getSecond());
                    }

                    final Type genericReturnType = method.getGenericReturnType();
                    if (genericReturnType instanceof ParameterizedType) {
                        final ParameterizedType parameterizedType = (ParameterizedType)genericReturnType;
                        final Class<?> rawType = (Class<?>)parameterizedType.getRawType();
                        if (Transformer.class.isAssignableFrom(rawType)) {
                            // Transformer<?, ?>
                            final Type[] typeArguments = parameterizedType.getActualTypeArguments();
                            if (typeArguments[0] instanceof Class && Interact.class.isAssignableFrom((Class<?>)typeArguments[0]) ) {
                                //  Transformer<Interact, ?>
                                final Type responseType = typeArguments[1];
                                if (responseType instanceof Class) {
                                    //  Transformer<Interact, R>
                                    LOG.debug("{}.{}.{}'s response as {}", api.getSimpleName(), builder.getSimpleName(), method.getName(), responseType);
                                    return interact.responseAs(getContentDecoder(method), (Class<?>)responseType);
                                } else if (responseType instanceof ParameterizedType) {
                                    if (FullMessage.class.isAssignableFrom((Class<?>)((ParameterizedType)responseType).getRawType())) {
                                        //  Transformer<Interact, FullMessage<MSG>>
                                        LOG.debug("{}.{}.{}'s response as FullMessage", api.getSimpleName(), builder.getSimpleName(), method.getName());
                                        return interact.response();
                                    }
                                }
                            }
                        }
                    }
                    LOG.error("unsupport {}.{}.{}'s return type: {}", api.getSimpleName(), builder.getSimpleName(), method.getName(), genericReturnType);
//                    final ResponseType responseType = method.getAnnotation(ResponseType.class);
//                    if (null != responseType) {
//                        return interact.responseAs(getContentDecoder(method), (Class<R>)responseType.value());
//                    } else {
//                        interact.response();
                        return Observable.error(new RuntimeException("Unknown Response Type"));
//                    }
                });
    }

    private static ContentDecoder getContentDecoder(final Method method) {
        final Consumes consumes = method.getAnnotation(Consumes.class);
        if (null != consumes) {
            return ContentUtil.selectCodec(consumes.value(), MIME_DECODERS);
        } else {
            return null;
        }
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

    private static Interact assignUriAndPath(final AnnotatedElement annotatedElement,
            final Map<String, Object> pathParams, final Interact interact) {
        if (null == annotatedElement) {
            return null;
        }
        final Path path = annotatedElement.getAnnotation(Path.class);
        if (null != path) {
            try {
                String uriAndPath = path.value();

                for (final Map.Entry<String, Object> entry : pathParams.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        uriAndPath = uriAndPath.replace("{" + entry.getKey() + "}", entry.getValue().toString());
                    }
                }

                final URI uri = new URI(uriAndPath);
                final String colonWithPort = uri.getPort() > 0 ? ":" + uri.getPort() : "";
                final String questionMarkWithQuery = uri.getQuery() != null && !uri.getQuery().isEmpty()
                        ? "?" + uri.getQuery()
                        : "";

                LOG.info("uri-- {}://{}{}{}{}", uri.getScheme(), uri.getHost(), colonWithPort, uri.getPath(), questionMarkWithQuery);
                return interact.uri(uri.getScheme() + "://" + uri.getHost() + colonWithPort).path(uri.getPath() + questionMarkWithQuery);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }
}
