package io.quarkiverse.rsocket.runtime;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

import javax.enterprise.inject.UnsatisfiedResolutionException;
import javax.enterprise.inject.spi.CDI;

import org.jboss.logging.Logger;
import org.reactivestreams.Publisher;

import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.metadata.CompositeMetadata;
import io.rsocket.metadata.TaggingMetadata;
import io.rsocket.metadata.WellKnownMimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class RoutedRsocket implements RSocket {
    private static final Logger LOGGER = Logger.getLogger(RoutedRsocket.class);
    private final Map<String, RequestResponseHandler<?>> requestResponseRoutes;
    private final Map<String, FireAndForgetHandler<?>> fireAndForgetRoutes;
    private final Map<String, RequestStreamHandler<?>> requestStreamRoutes;
    private final Map<String, RequestChannelHandler<?>> requestChannelRoutes;
    private final EncoderManager encoderRegistry;
    private String mimeType = WellKnownMimeType.APPLICATION_JSON.getString();

    RoutedRsocket(Map<String, RequestResponseHandler<?>> requestResponseRoutes,
            Map<String, FireAndForgetHandler<?>> fireAndForgetRoutes,
            Map<String, RequestStreamHandler<?>> requestStreamRoutes,
            Map<String, RequestChannelHandler<?>> requestChannelRoutes) {
        this.requestResponseRoutes = requestResponseRoutes;
        this.fireAndForgetRoutes = fireAndForgetRoutes;
        this.requestStreamRoutes = requestStreamRoutes;
        this.requestChannelRoutes = requestChannelRoutes;
        encoderRegistry = new EncoderManager();
    }

    public static Builder builder() {
        return new Builder();
    }

    public void setMimeType(String mimetype) {
        this.mimeType = mimetype;
    }

    private static Class<?> loadClass(String className) {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            //silent fail
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Object getInstance(String className) {
        Class<?> classObj = loadClass(className);
        if (classObj == null) {
            return null;
        }

        try {
            return CDI.current().select(classObj).get();
        } catch (UnsatisfiedResolutionException | IllegalStateException e) {
            //silent fail
        }

        try {
            return classObj.getConstructor().newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    public static final class Builder {
        private final Map<String, RequestResponseHandler<?>> requestResponseRoutes;
        private final Map<String, FireAndForgetHandler<?>> fireAndForgetRoutes;
        private final Map<String, RequestStreamHandler<?>> requestStreamRoutes;
        private final Map<String, RequestChannelHandler<?>> requestChannelRoutes;

        public Builder() {
            this.requestResponseRoutes = new HashMap<>();
            this.fireAndForgetRoutes = new HashMap<>();
            this.requestStreamRoutes = new HashMap<>();
            this.requestChannelRoutes = new HashMap<>();
        }

        public Builder addRequestResponse(String route, String handlerClassName) {
            RequestResponseHandler<?> handler = (RequestResponseHandler<?>) getInstance(handlerClassName);
            requestResponseRoutes.put(route, handler);
            return this;
        }

        public Builder addFireAndForget(String route, String handlerClassName) {
            FireAndForgetHandler<?> handler = (FireAndForgetHandler<?>) getInstance(handlerClassName);
            fireAndForgetRoutes.put(route, handler);
            return this;
        }

        public Builder addRequestStream(String route, String handlerClassName) {
            RequestStreamHandler<?> handler = (RequestStreamHandler<?>) getInstance(handlerClassName);
            requestStreamRoutes.put(route, handler);
            return this;
        }

        public Builder addRequestChannel(String route, String handlerClassName) {
            RequestChannelHandler<?> handler = (RequestChannelHandler<?>) getInstance(handlerClassName);
            requestChannelRoutes.put(route, handler);
            return this;
        }

        public RoutedRsocket build() {
            return new RoutedRsocket(requestResponseRoutes, fireAndForgetRoutes, requestStreamRoutes, requestChannelRoutes);
        }

    }

    @Override
    public Mono<Payload> requestResponse(Payload payload) {
        try {
            LOGGER.debug("requestResponse called");
            Map<String, TaggingMetadata> metadatas = parseMetadata(payload);
            String route = getRoute(metadatas);
            LOGGER.debug("route :" + route);
            if (route != null) {

                RequestResponseHandler<?> handler = requestResponseRoutes.get(route);
                if (handler != null) {
                    Class<?> persistentClass = getHandlerGenericParam(handler);

                    LOGGER.debug("handler found");
                    Encoder encoder = getEncoder(metadatas);
                    Object obj = encoder.decode(payload, persistentClass);
                    return handleRequestResponse(handler, obj).map(encoder::encode);
                }
            }
            LOGGER.debug("handler not found");
            return RSocket.super.requestResponse(payload);
        } catch (Throwable t) {
            LOGGER.error("request response error", t);
            return Mono.error(t);
        }
    }

    private Class<?> getHandlerGenericParam(Object handler) {
        for (Type itf : handler.getClass().getGenericInterfaces()) {
            if (itf instanceof ParameterizedType) {
                String itfRawtype = ((ParameterizedType) itf).getRawType().getTypeName();
                if (itfRawtype.equals("io.quarkiverse.rsocket.runtime.RequestResponseHandler")
                        || itfRawtype.equals("io.quarkiverse.rsocket.runtime.FireAndForgetHandler")
                        || itfRawtype.equals("io.quarkiverse.rsocket.runtime.RequestStreamHandler")
                        || itfRawtype.equals("io.quarkiverse.rsocket.runtime.RequestChannelHandler")) {
                    return (Class<?>) ((ParameterizedType) itf).getActualTypeArguments()[0];
                }
            }
        }
        return null;
    }

    private <T> Mono<T> handleRequestResponse(RequestResponseHandler<T> handler, Object obj) {
        return handler.handle((T) obj);
    }

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
        try {
            Map<String, TaggingMetadata> metadatas = parseMetadata(payload);
            String route = getRoute(metadatas);
            if (route != null) {
                FireAndForgetHandler<?> handler = fireAndForgetRoutes.get(route);
                if (handler != null) {
                    Class<?> persistentClass = getHandlerGenericParam(handler);
                    Encoder encoder = getEncoder(metadatas);
                    return handleFireAndForget(handler, encoder.decode(payload, persistentClass));
                }
            }
            return RSocket.super.fireAndForget(payload);
        } catch (Throwable t) {
            return Mono.error(t);
        }
    }

    private <T> Mono<Void> handleFireAndForget(FireAndForgetHandler<T> handler, Object obj) {
        return handler.handle((T) obj);
    }

    @Override
    public Flux<Payload> requestStream(Payload payload) {
        try {
            Map<String, TaggingMetadata> metadatas = parseMetadata(payload);
            String route = getRoute(metadatas);
            if (route != null) {
                RequestStreamHandler<?> handler = requestStreamRoutes.get(route);
                if (handler != null) {
                    Class<?> persistentClass = getHandlerGenericParam(handler);
                    Encoder encoder = getEncoder(metadatas);
                    return handleRequestStream(handler, encoder.decode(payload, persistentClass)).map(encoder::encode);
                }
            }
            return RSocket.super.requestStream(payload);
        } catch (Throwable t) {
            return Flux.error(t);
        }
    }

    private <T> Flux<T> handleRequestStream(RequestStreamHandler<T> handler, Object obj) {
        return handler.handle((T) obj);
    }

    @Override
    public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
        return Flux.from(payloads)
                .switchOnFirst(
                        (signal, flows) -> {
                            Payload payload = null;
                            try {
                                payload = signal.get();
                                if (payload != null) {

                                    Map<String, TaggingMetadata> metadatas = parseMetadata(payload);
                                    String route = getRoute(metadatas);
                                    if (route != null) {
                                        RequestChannelHandler<?> handler = requestChannelRoutes.get(route);
                                        if (handler != null) {
                                            Class<?> persistentClass = getHandlerGenericParam(handler);
                                            Encoder encoder = getEncoder(metadatas);
                                            return handleRequestChannel(handler,
                                                    flows.map(pl -> encoder.decode(pl, persistentClass))).map(encoder::encode);
                                        }
                                    }
                                }
                                return RSocket.super.requestChannel(payloads);
                            } catch (Throwable t) {
                                if (payload != null) {
                                    payload.release();
                                }
                                return Flux.error(t);
                            }
                        },
                        false);

    }

    private <T> Flux<T> handleRequestChannel(RequestChannelHandler<T> handler, Object obj) {
        return handler.handle((Flux<T>) obj);
    }

    private Map<String, TaggingMetadata> parseMetadata(Payload payload) {
        Map<String, TaggingMetadata> metadataMap = new HashMap<>();

        if (payload.hasMetadata()) {
            CompositeMetadata compositeMetadata = new CompositeMetadata(payload.metadata(), true);

            for (CompositeMetadata.Entry entry : compositeMetadata) {
                if (entry instanceof CompositeMetadata.WellKnownMimeTypeEntry) {
                    TaggingMetadata metadata = new TaggingMetadata(entry.getMimeType(), entry.getContent());

                    metadataMap.put(entry.getMimeType(), metadata);
                }
            }
        }
        return metadataMap;
    }

    private Encoder getEncoder(Map<String, TaggingMetadata> metadatas) {
        //PerStreamDataMimeTypes
        TaggingMetadata mimetypeMetadata = metadatas.get(WellKnownMimeType.MESSAGE_RSOCKET_MIMETYPE.getString());
        Encoder res = null;
        if (mimetypeMetadata != null && mimetypeMetadata.iterator().hasNext()) {
            res = encoderRegistry.getEncoder(mimetypeMetadata.iterator().next());
        }
        if (res != null)
            return res;
        // else encoder from ConnectionSetupPayload
        return encoderRegistry.getEncoder(mimeType);
    }

    private String getRoute(Map<String, TaggingMetadata> metadatas) {
        TaggingMetadata routeMetadata = metadatas.get(WellKnownMimeType.MESSAGE_RSOCKET_ROUTING.getString());
        if (routeMetadata != null && routeMetadata.iterator().hasNext()) {
            return routeMetadata.iterator().next();
        }
        return null;
    }
}
