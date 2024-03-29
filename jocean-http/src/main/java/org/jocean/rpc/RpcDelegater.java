/**
 *
 */
package org.jocean.rpc;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
import org.jocean.http.MessageUtil;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Pair;
import org.jocean.idiom.ReflectUtils;
import org.jocean.rpc.annotation.ConstParams;
import org.jocean.rpc.annotation.OnBuild;
import org.jocean.rpc.annotation.OnHttpResponse;
import org.jocean.rpc.annotation.OnInteract;
import org.jocean.rpc.annotation.OnParam;
import org.jocean.rpc.annotation.OnResponse;
import org.jocean.rpc.annotation.RpcResource;
import org.jocean.rpc.annotation.RpcRetry;
import org.jocean.rpc.annotation.RpcTimeout;
import org.jocean.rpc.annotation.StatusCodeAware;
import org.jocean.rpc.annotation.ToResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.annotation.JSONField;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import rx.Observable;
import rx.Observable.Transformer;
import rx.functions.Action2;
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
        Builder<BUILDER> constParamCarriers(final AnnotatedElement... carriers);
        Builder<BUILDER> pathCarriers(final AnnotatedElement... carriers);
        Builder<BUILDER> owner(final Class<?> owner);
        Builder<BUILDER> emitter(Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> emitter);
        BUILDER build();
    }

    static public <BUILDER> Builder<BUILDER> rpc(final Class<BUILDER> builderType) {
        final Context ictx = new Context(builderType);

        return new Builder<BUILDER>() {

            @Override
            public Builder<BUILDER> constParamCarriers(final AnnotatedElement... carriers) {
                ictx.constParamCarriers = carriers;
                return this;
            }

            @Override
            public Builder<BUILDER> pathCarriers(final AnnotatedElement... carriers) {
                ictx.pathCarriers = carriers;
                return this;
            }

            @Override
            public Builder<BUILDER> owner(final Class<?> owner) {
                ictx.builderOwner = owner;
                return this;
            }

            @Override
            public Builder<BUILDER> emitter(
                    final Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> emitter) {
                ictx.emitter = emitter;
                return this;
            }

            @Override
            public BUILDER build() {
                return proxyBuilder(ictx);
            }};

    }

    static private class Context {
        Context(final Class<?> builderType) {
            this.builderType = builderType;
        }

        AnnotatedElement[] constParamCarriers;
        AnnotatedElement[] pathCarriers;

        Class<?> builderOwner;
        Func1<Transformer<Interact, ? extends Object>, Observable<? extends Object>> emitter;

        final Class<?> builderType;

        final Map<String, Object> rpcResources = new HashMap<>();
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
    private static <BUILDER> BUILDER proxyBuilder(final Context ictx) {
        return (BUILDER)Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] { ictx.builderType }, rpcBuilderHandler(ictx) );
    }

    private static InvocationHandler rpcBuilderHandler(final Context ictx) {
        return new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method, final Object[] args)
                    throws Throwable {
                if (null != args && args.length == 1 && Object.class.isAssignableFrom(method.getReturnType())) {
                    // return ? extends Object with one param
                    //  XXXBuilder item1(final String value)
                    final OnBuild onBuild = method.getAnnotation(OnBuild.class);
                    final RpcResource rpcResource = method.getAnnotation(RpcResource.class);
                    final QueryParam queryParam = method.getAnnotation(QueryParam.class);
                    final JSONField jsonField = method.getAnnotation(JSONField.class);
                    final PathParam pathParam = method.getAnnotation(PathParam.class);
                    final HeaderParam headerParam = method.getAnnotation(HeaderParam.class);
                    final Produces produces = method.getAnnotation(Produces.class);
                    final OnParam onParam = method.getAnnotation(OnParam.class);

                    if (null != onBuild) {
                        final Action2<Object, Object> applier = ReflectUtils.getStaticFieldValue(onBuild.value());
                        if (null != applier) {
                            LOG.debug("invoke Builder applier:{} by {}/{}", applier, proxy, args[0]);
                            try {
                                applier.call(proxy, args[0]);
                            } catch (final Exception e) {
                                LOG.warn("exception when invoke Builder applier {}, detail: {}", applier, ExceptionUtils.exception2detail(e));
                            }
                        }
                    } else if (null != rpcResource) {
                        ictx.rpcResources.put(rpcResource.value(), handleOnParam(onParam, args[0]));
                    } else if (null != queryParam) {
                        ictx.queryParams.put(queryParam.value(), handleOnParam(onParam, args[0]));
                    } else if (null != pathParam) {
                        ictx.pathParams.put(pathParam.value(), handleOnParam(onParam, args[0]));
                    } else if (null != headerParam) {
                        ictx.headerParams.put(headerParam.value(), handleOnParam(onParam, args[0]));
                    } else if (null != jsonField && !jsonField.name().isEmpty()) {
                        ictx.jsonFields.put(jsonField.name(), handleOnParam(onParam, args[0]));
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
                } else if ( (null == args || args.length == 0)
                        && ( isObservableAny(method.getGenericReturnType())
                            || isInteract2Any(method.getGenericReturnType()) ) ) {
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

                        return ictx.emitter.call(interact2obj(ictx, method, responseType));
                    }
                    else if (isInteract2Any(method.getGenericReturnType())) {
                        // Transformer<Interact, XXX> call()
                        final Type responseType = ReflectUtils.getParameterizedTypeArgument(method.getGenericReturnType(), 1);
                        return interact2obj(ictx, method, responseType);
                    }
                    LOG.error("unsupport {}.{}.{}'s return type: {}", ictx.builderOwnerName(), ictx.builderType.getSimpleName(),
                            method.getName(), method.getReturnType());
                } else if (method.getName().equals("toString") && method.getReturnType().equals(String.class)) {
                    // invoke toString()
                    return ictx.builderType.getSimpleName() + "@" + Integer.toHexString(Proxy.getInvocationHandler(proxy).hashCode());
                } else {
                    LOG.error("unsupport {}.{}.{}'s return type: {}", ictx.builderOwnerName(), ictx.builderType.getSimpleName(),
                            method.getName(), method.getReturnType());
                }

                return null;
            }
        };
    }

    private static Object handleOnParam(final OnParam onParam, final Object arg0) {
        if (null == onParam) {
            return arg0;
        }
        try {
            final Method transf = ReflectUtils.getMethodByFullname(onParam.value());
            return transf.invoke(null, arg0);
        } catch (final Exception e) {
            LOG.warn("exception when handleOnParam for {}, detail: {}", onParam.value(), ExceptionUtils.exception2detail(e));
            return arg0;
        }
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
            final Context ictx,
            final Method callMethod,
            final Type responseType
            ) {
        return interacts -> interacts.compose(handleOnInteract(ictx, callMethod.getAnnotation(OnInteract.class)))
                .flatMap(interact ->
                    setupInteract(interact, ictx, callMethod)
                    .response()
                    .compose(handleTimeout(callMethod.getAnnotation(RpcTimeout.class)))
                    .compose(handleRetry(callMethod.getAnnotation(RpcRetry.class)))
                    .compose(handleOnHttpResponse(callMethod.getAnnotation(OnHttpResponse.class)))
                    .compose(toFinalResponse(ictx, callMethod, responseType))
                );
    }

    private static Transformer<FullMessage<HttpResponse>, FullMessage<HttpResponse>> handleTimeout(final RpcTimeout rpcTimeout) {
        return fmrs -> {
            if (null == rpcTimeout) {
                // 默认 30s 超时
                return fmrs.timeout(30 * 1000, TimeUnit.MILLISECONDS);
            } else {
                return fmrs.timeout(rpcTimeout.value(), TimeUnit.MILLISECONDS);
            }
        };
    }

    private static Transformer<FullMessage<HttpResponse>, FullMessage<HttpResponse>> handleRetry(final RpcRetry rpcRetry) {
        return fmrs -> {
            if (null == rpcRetry) {
                return fmrs;
            } else {
                return fmrs.retry(rpcRetry.value());
            }
        };
    }

    private static Transformer<FullMessage<HttpResponse>, Object> toFinalResponse(final Context ictx, final Method callMethod, final Type responseType) {
        if (responseType instanceof Class) {
            //  Observable<R>
            LOG.debug("{}.{}.{}'s response as {}", ictx.builderOwnerName(),
                    ictx.builderType.getSimpleName(), callMethod.getName(), responseType);

            return fmrs -> {
                final Observable<FullMessage<HttpResponse>> cachedFmrs = fmrs.cache();
                return cachedFmrs.flatMap(fmr -> cachedFmrs.compose(
                        genToResponse(ictx, callMethod.getAnnotation(ToResponse.class), callMethod, (Class<?>)responseType))
                        .compose(handleResponseByHttpResponse(fmr.message(), (Class<?>)responseType)))
                    .compose(handleOnResponse(callMethod.getAnnotation(OnResponse.class)));
            };
        } else if (responseType instanceof ParameterizedType) {
            if (FullMessage.class.isAssignableFrom((Class<?>)((ParameterizedType)responseType).getRawType())) {
                //  Observable<FullMessage<MSG>>
                LOG.debug("{}.{}.{}'s response as FullMessage<HttpResponse>", ictx.builderOwnerName(),
                        ictx.builderType.getSimpleName(), callMethod.getName());
                return fmrs -> fmrs.<Object>map(resp -> resp);
            }
        }
        LOG.error("unsupport {}.{}.{}'s return type: {}", ictx.builderOwnerName(), ictx.builderType.getSimpleName(), callMethod.getName(), responseType);
        return fmrs -> Observable.error(new RuntimeException("Unknown Response Type"));
    }

    private static Transformer<Object, Object> handleResponseByHttpResponse(final HttpResponse httpResp, final Class<?> responseType) {
        return objs -> objs.doOnNext(obj -> {
            final Method[] methods = ReflectUtils.getAllMethodsOfClass(responseType);
            for (final Method m : methods) {
                final HeaderParam headerParam = m.getAnnotation(HeaderParam.class);
                if (headerParam != null && (m.getParameters() != null && m.getParameters().length == 1)) {
                    final String value = httpResp.headers().get(headerParam.value());
                    try {
                        m.invoke(obj, value);
                    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                        LOG.warn("exception when invoke header method:{}, detail: {}", m.getName(), ExceptionUtils.exception2detail(e));
                    }
                } else if (m.getAnnotation(StatusCodeAware.class) != null && (m.getParameters() != null && m.getParameters().length == 1)) {
                    try {
                        m.invoke(obj, httpResp.status().code());
                    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                        LOG.warn("exception when invoke status method:{}, detail: {}", m.getName(), ExceptionUtils.exception2detail(e));
                    }
                }
            }
        });
    }

    private static Interact setupInteract(Interact interact, final Context ictx, final Method callMethod) {
        {
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
        return interact;
    }

    private static Transformer<FullMessage<HttpResponse>, Object> genToResponse(final Context ictx,
            final ToResponse toResponse, final Method callMethod, final Class<?> responseType) {
        if (null == toResponse) {
            return fullhttpresps -> fullhttpresps.flatMap(MessageUtil.fullmsg2body())
                    .compose(MessageUtil.body2bean(getContentDecoder(callMethod), responseType));
                    // interact.responseAs(getContentDecoder(callMethod), (Class<?>)responseType);
        }
        if (toResponse.value().endsWith("()")) {
            try {
                final Method torespmethod = ReflectUtils.getStaticMethod(toResponse.value().substring(0, toResponse.value().length() - 2));
                @SuppressWarnings("unchecked")
                final Transformer<FullMessage<HttpResponse>, Object> toresp = (Transformer<FullMessage<HttpResponse>, Object>) torespmethod.invoke(null);
                if (null == toresp) {
                    throw new NullPointerException("invoke " + toResponse.value() + " and return null");
                }
                if (toresp instanceof ParamAware) {
                    processParamAware(QueryParam.class, ictx.queryParams, ((ParamAware)toresp));
                    processParamAware(PathParam.class, ictx.pathParams, ((ParamAware)toresp));
                    processParamAware(HeaderParam.class, ictx.headerParams, ((ParamAware)toresp));
                    processParamAware(JSONField.class, ictx.jsonFields, ((ParamAware)toresp));
                }
                return toresp;
            } catch (final Exception e) {
                LOG.warn("exception when invoke torespmethod:{}, detail: {}", toResponse.value(), ExceptionUtils.exception2detail(e));
                return fullhttpresps -> Observable.error(e);
            }
        } else {
            return ReflectUtils.getStaticFieldValue(toResponse.value());
        }
    }

    @SuppressWarnings("unchecked")
    private static Transformer<Object, Object> handleOnResponse(final OnResponse onResponse) {
        if (null != onResponse) {
            return transformerOf(onResponse.value(),
                    s -> (Transformer<Object, Object>)ReflectUtils.getStaticFieldValue(s));
        }
        return objs -> objs;
    }

    @SuppressWarnings("unchecked")
    private static Transformer<FullMessage<HttpResponse>, FullMessage<HttpResponse>> handleOnHttpResponse(
            final OnHttpResponse onHttpResponse) {
        if (null != onHttpResponse) {
            return transformerOf(onHttpResponse.value(),
                    s -> (Transformer<FullMessage<HttpResponse>, FullMessage<HttpResponse>>)ReflectUtils.getStaticFieldValue(s));
        }
        return fmrs -> fmrs;
    }

    @SuppressWarnings("unchecked")
    private static Transformer<Interact, Interact> handleOnInteract(final Context ictx, final OnInteract onInteract) {
        if (null != onInteract) {
            return transformerOf(onInteract.value(), s -> (Transformer<Interact, Interact>) ictx.rpcResources.get(s));
        }
        return interacts -> interacts;
    }

    private static void processParamAware(final Class<?> paramType, final Map<String, Object> params, final ParamAware paramAware) {
        for (final Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                try {
                    paramAware.setParam(paramType, entry.getKey(), entry.getValue());
                } catch (final Exception e) {
                    LOG.warn("exception when invoke {}.setParam, detail: {}", paramAware, ExceptionUtils.exception2detail(e));
                }
            }
        }

    }

    private static <T> Transformer<T, T> transformerOf(final String[] vars, final Func1<String, Transformer<T, T>> s2t) {
        final AtomicReference<Transformer<T, T>> transformerRef = new AtomicReference<>(null);
        for (final String var : vars) {
            try {
                final Transformer<T, T> suff = s2t.call(var);
                if (null == suff) {
                    LOG.warn("transformerOf: get transformer by {} is null", var);
                    return any -> Observable.error(new NullPointerException("missing transformer named:" + var));
                }
                LOG.debug("transformerOf: get Transformer<T, T> {} by {}", suff, var);
                if (null != transformerRef.get()) {
                    final Transformer<T, T> prev = transformerRef.get();
                    transformerRef.set(objs -> objs.compose(prev).compose(suff));
                } else {
                    transformerRef.set(suff);
                }
            } catch (final Exception e) {
                LOG.warn("exception when invoke transformerOf, detail: {}", ExceptionUtils.exception2detail(e));
                return any -> Observable.error(e);
            }
        }
        return null != transformerRef.get() ? transformerRef.get() : any -> any;
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
