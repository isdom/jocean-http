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
import java.util.Arrays;
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
import org.jocean.idiom.Pair;
import org.jocean.idiom.ReflectUtils;
import org.jocean.rpc.annotation.ConstParams;
import org.jocean.rpc.annotation.OnResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action1;
import rx.functions.Func1;

/**
 * @author isdom
 *
 */
public class RpcDelegater {
    private static final Logger LOG = LoggerFactory.getLogger(RpcDelegater.class);

    static final ContentEncoder[] MIME_ENCODERS = new ContentEncoder[]{
            ContentUtil.TOJSON, ContentUtil.TOXML, ContentUtil.TOTEXT, ContentUtil.TOHTML};

    static final ContentDecoder[] MIME_DECODERS = new ContentDecoder[]{ContentUtil.ASJSON, ContentUtil.ASXML, ContentUtil.ASTEXT};

    public interface Builder<BUILDER> {
        Builder<BUILDER> constParamCarriers(final AnnotatedElement[] carriers);
        Builder<BUILDER> pathCarriers(final AnnotatedElement[] carriers);
        Builder<BUILDER> owner(final Class<?> owner);
        Builder<BUILDER> invoker(Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> invoker);
        BUILDER build();
    }

    static public <BUILDER> Builder<BUILDER> rpc(final Class<BUILDER> builderType) {
        final InvocationContext ictx = new InvocationContext(builderType);
        final AtomicReference<Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>>> invokerRef = new AtomicReference<>();
        return new Builder<BUILDER>() {

            @Override
            public Builder<BUILDER> constParamCarriers(final AnnotatedElement[] carriers) {
                ictx.constParamCarriers = carriers;
                return this;
            }

            @Override
            public Builder<BUILDER> pathCarriers(final AnnotatedElement[] carriers) {
                ictx.pathCarriers = carriers;
                return this;
            }

            @Override
            public Builder<BUILDER> owner(final Class<?> owner) {
                ictx.builderOwner = owner;
                return this;
            }

            @Override
            public Builder<BUILDER> invoker(
                    final Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> invoker) {
                invokerRef.set(invoker);
                return this;
            }

            @Override
            public BUILDER build() {
                return proxyBuilder(ictx, invokerRef.get());
            }};

    }

    static public class InvocationContext {
        public InvocationContext(final Class<?> builderType) {
            this.builderType = builderType;
        }

        public AnnotatedElement[] constParamCarriers;
        public AnnotatedElement[] pathCarriers;

        public Class<?> builderOwner;

        final Class<?> builderType;

        final Map<String, Object> queryParams = new HashMap<>();
        final Map<String, Object> pathParams = new HashMap<>();
        final Map<String, Object> headerParams = new HashMap<>();
        final JSONObject jsonFields = new JSONObject();
        Observable<? extends MessageBody> body = null;
        Pair<Object, ContentEncoder> content = null;

        String builderOwnerName() {
            return null != builderOwner ? builderOwner.getSimpleName() : "(null)";
        }
    }

    @SuppressWarnings("unchecked")
    public static <BUILDER> BUILDER proxyBuilder(
            final InvocationContext ictx,
            final Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> invoker) {
        return (BUILDER)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] { ictx.builderType }, rpcBuilderHandler(ictx, invoker) );
    }

    public static InvocationHandler rpcBuilderHandler(
            final InvocationContext ictx,
            final Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> invoker) {
        return new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method, final Object[] args)
                    throws Throwable {
                if (null != args && args.length == 1 && Object.class.isAssignableFrom(method.getReturnType())) {
                    // return ? extends Object with one param
                    //  XXXBuilder item1(final String value)
                    final QueryParam queryParam = method.getAnnotation(QueryParam.class);
                    final JSONField jsonField = method.getAnnotation(JSONField.class);
                    final PathParam pathParam = method.getAnnotation(PathParam.class);
                    final HeaderParam headerParam = method.getAnnotation(HeaderParam.class);
                    final Produces produces = method.getAnnotation(Produces.class);
                    if (null != queryParam) {
                        ictx.queryParams.put(queryParam.value(), args[0]);
                    } else if (null != pathParam) {
                        ictx.pathParams.put(pathParam.value(), args[0]);
                    } else if (null != headerParam) {
                        ictx.headerParams.put(headerParam.value(), args[0]);
                    } else if (null != jsonField && !jsonField.name().isEmpty()) {
                        ictx.jsonFields.put(jsonField.name(), args[0]);
                    } else if (null != produces) {
                        final ContentEncoder bodyEncoder = ContentUtil.selectCodec(produces.value(), MIME_ENCODERS);
                        if (null != bodyEncoder) {
                            ictx.content = Pair.of(args[0], bodyEncoder);
                        }
                    } else {
                        final Class<?> arg1stType = method.getParameterTypes()[0];

                        if (MessageBody.class.isAssignableFrom(arg1stType)) {
                            // means: API body(final MessageBody body); not care method name
                            ictx.body = Observable.just((MessageBody)args[0]);
                        } else if (arg1stType.equals(Observable.class)) {
                            final Type arg1stGenericType = method.getGenericParameterTypes()[0];
                            if ( arg1stGenericType instanceof ParameterizedType ) {
                                final Type actualGenericType = ((ParameterizedType)arg1stGenericType).getActualTypeArguments()[0];
                                if (actualGenericType instanceof Class
                                    && MessageBody.class.isAssignableFrom((Class<?>)actualGenericType)) {
                                    // Observable<MessageBody> or Observable<XXXMessageBody>
                                    ictx.body = (Observable<? extends MessageBody>)args[0];
                                }
                                // TODO
//                                else if (arg1stGenericType instanceof WildcardType) {
//                                }
                            }
                        }
                    }
                    return proxy;
                } else if (null == args || args.length == 0) {
                    if (null != ictx.constParamCarriers) {
                        for (final AnnotatedElement annotatedElement : ictx.constParamCarriers) {
                            addConstParams(method, annotatedElement, ictx.queryParams);
                        }
                    }
                    addConstParams(method, method, ictx.queryParams);

                    if (!ictx.jsonFields.isEmpty()) {
                        LOG.debug("generate JSON Object for fields: {}", Arrays.toString(ictx.jsonFields.keySet().toArray(new String[0])));
                        if (ictx.content != null) {
                            LOG.warn("body assign {} will be override by JSONFields {}", ictx.content.getFirst(),
                                    Arrays.toString(ictx.jsonFields.keySet().toArray(new String[0])));
                        }
                        ictx.content = Pair.of(ictx.jsonFields, ContentUtil.TOJSON);
                    }
                    if (isObservableAny(method.getGenericReturnType())) {
                        // Observable<XXX> call()
                        final Type responseType = ReflectUtils.getParameterizedTypeArgument(method.getGenericReturnType(), 0);

                        return invoker.call(interact2obj(ictx, method, responseType));
                    }
                    else if (isInteract2Any(method.getGenericReturnType())) {
                        // Transformer<Interact, XXX> call()
                        final Type responseType = ReflectUtils.getParameterizedTypeArgument(method.getGenericReturnType(), 1);
                        return interact2obj(ictx, method, responseType);
                    }
                    LOG.error("unsupport {}.{}.{}'s return type: {}", ictx.builderOwnerName(), ictx.builderType.getSimpleName(),
                            method.getName(), method.getReturnType());
                }

                return null;
            }
        };
    }

    public static boolean isObservableAny(final Type genericType) {
        final Class<?> rawType = ReflectUtils.getParameterizedRawType(genericType);
        return null != rawType && Observable.class.isAssignableFrom(rawType);
    }

    public static boolean isInteract2Any(final Type genericType) {
        final Class<?> rawType = ReflectUtils.getParameterizedRawType(genericType);
        return null != rawType && Transformer.class.isAssignableFrom(rawType)
                && Interact.class.equals(ReflectUtils.getParameterizedTypeArgument(genericType, 0));
    }

    private static Transformer<Interact, ? extends Object> interact2obj(
            final InvocationContext ictx,
            final Method callMethod,
            final Type responseType
            ) {
        return interacts -> interacts.flatMap(interact -> {
                    Interact newInteract = null;
                    if (null != ictx.pathCarriers) {
                        for (final AnnotatedElement annotatedElement : ictx.pathCarriers) {
                            newInteract = assignUriAndPath(callMethod, annotatedElement, ictx.pathParams, interact);
                            if (null != newInteract) {
                                interact = newInteract;
                                break;
                            }
                        }
                    }
                    if (null == newInteract) {
                        newInteract = assignUriAndPath(callMethod, callMethod, ictx.pathParams, interact);
                        if (null != newInteract) {
                            interact = newInteract;
                        }
                    }

                    interact = interact.method(getHttpMethod(callMethod));

                    for (final Map.Entry<String, Object> entry : ictx.queryParams.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            interact = interact.paramAsQuery(entry.getKey(), entry.getValue().toString());
                        }
                    }

                    if (!ictx.headerParams.isEmpty()) {
                        // set headers
                        interact = interact.onrequest(obj -> {
                            if (obj instanceof HttpRequest) {
                                for (final Map.Entry<String, Object> entry : ictx.headerParams.entrySet()) {
                                    if (entry.getKey() != null && entry.getValue() != null) {
                                        ((HttpRequest)obj).headers().set(entry.getKey(), entry.getValue());
                                    }
                                }
                            }
                        });
                    }

                    if (null != ictx.body) {
                        interact = interact.body(ictx.body);
                    } else if ( null != ictx.content) {
                        interact = interact.body(ictx.content.getFirst(), ictx.content.getSecond());
                    }

                    /*
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
                    */
                    if (responseType instanceof Class) {
                        //  Transformer<Interact, R>
                        LOG.debug("{}.{}.{}'s response as {}", ictx.builderOwnerName(), ictx.builderType.getSimpleName(), callMethod.getName(), responseType);
                        Action1<Object> onresp = null;
                        final OnResponse onResponse = callMethod.getAnnotation(OnResponse.class);
                        if (null != onResponse) {
                            onresp = onNextOf(onResponse.value(), (Class<?>)responseType);
                        }
                        final Observable<? extends Object> getresponse = interact.responseAs(getContentDecoder(callMethod), (Class<?>)responseType);
                        return null != onresp ? getresponse.doOnNext(onresp) : getresponse;
                    } else if (responseType instanceof ParameterizedType) {
                        if (FullMessage.class.isAssignableFrom((Class<?>)((ParameterizedType)responseType).getRawType())) {
                            //  Transformer<Interact, FullMessage<MSG>>
                            LOG.debug("{}.{}.{}'s response as FullMessage", ictx.builderOwnerName(), ictx.builderType.getSimpleName(), callMethod.getName());
                            Action1<Object> onresp = null;
                            final OnResponse onResponse = callMethod.getAnnotation(OnResponse.class);
                            if (null != onResponse) {
                                onresp = onNextOf(onResponse.value(), (Class<?>)responseType);
                            }
                            final Observable<? extends Object> getresponse = interact.response();
                            return null != onresp ? getresponse.doOnNext(onresp) : getresponse;
                        }
                    }
                    LOG.error("unsupport {}.{}.{}'s return type: {}", ictx.builderOwnerName(), ictx.builderType.getSimpleName(), callMethod.getName(), responseType);
                    return Observable.error(new RuntimeException("Unknown Response Type"));
                });
    }

    private static Action1<Object> onNextOf(final String[] vars, final Class<?> type) {
        final AtomicReference<Action1<Object>> onNextRef = new AtomicReference<>(null);
        for (final String var : vars) {
            final Action1<Object> suff = ReflectUtils.getStaticFieldValue(var);
            LOG.debug("get Action1<Object> {} by {}", suff, var);
            if (null != onNextRef.get()) {
                final Action1<Object> prev = (Action1<Object>) onNextRef.get();
                onNextRef.set(obj -> {
                    prev.call(obj);
                    suff.call(obj);
                });
            } else {
                onNextRef.set(suff);
            }
        }
        return onNextRef.get();
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

    private static void addConstParams(
            final Method callMethod,
            final AnnotatedElement annotatedElement,
            final Map<String, Object> params) {
        if (null == annotatedElement) {
            return;
        }
        final ConstParams constParams = annotatedElement.getAnnotation(ConstParams.class);
        // add const params mark by XXXBuilder.call method
        if (null != constParams) {
            final String keyValues[] = constParams.value();
            LOG.debug("prepare rpc[{}.{}], found @ConstParams by {}, const params detail: {} ",
                    callMethod.getDeclaringClass().getSimpleName(),
                    callMethod.getName(),
                    annotatedElement,
                    Arrays.toString(keyValues));
            for (int i = 0; i < keyValues.length-1; i+=2) {
                params.put(keyValues[i], keyValues[i+1]);
            }
        }
    }

    private static Interact assignUriAndPath(
            final Method callMethod,
            final AnnotatedElement annotatedElement,
            final Map<String, Object> pathParams,
            Interact interact) {
        if (null == annotatedElement) {
            return null;
        }
        final Path path = annotatedElement.getAnnotation(Path.class);
        if (null != path) {
            LOG.debug("prepare rpc[{}.{}], found @Path by {}, path detail: {} ",
                    callMethod.getDeclaringClass().getSimpleName(),
                    callMethod.getName(),
                    annotatedElement,
                    path.value());
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

                LOG.info("uri -- {}://{}{}{}{}", uri.getScheme(), uri.getHost(), colonWithPort, uri.getPath(), questionMarkWithQuery);
                if (null != uri.getScheme() && null != uri.getHost()) {
                    interact = interact.uri(uri.getScheme() + "://" + uri.getHost() + colonWithPort);
                }
                return interact.path(uri.getPath() + questionMarkWithQuery);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            return null;
        }
    }
}
