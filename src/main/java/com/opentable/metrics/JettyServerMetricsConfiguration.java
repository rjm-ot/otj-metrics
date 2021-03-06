/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.opentable.metrics;

import java.util.function.Consumer;
import java.util.function.Function;

import javax.inject.Provider;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jetty9.InstrumentedHandler;
import com.codahale.metrics.jetty9.InstrumentedQueuedThreadPool;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.opentable.conservedheaders.ConservedHeader;

/**
 * Provides two Beans&mdash;one for a provider of instrumented queued thread pools,
 * and the other for a metrics-instrumented handler customizer.  These are both picked up by {@code EmbeddedJetty}
 * in {@code otj-server}.
 */
@Configuration
public class JettyServerMetricsConfiguration {
    private static final String PREFIX = "http-server";

    @Bean
    public Provider<QueuedThreadPool> getIQTPProvider(final MetricRegistry metricRegistry, @Value("${ot.httpserver.queue-size:128}") int qSize) {
        return () -> {
            final InstrumentedQueuedThreadPool pool = new OTQueuedThreadPool(metricRegistry, qSize);
            pool.setName("default-pool");
            return pool;
        };
    }

    @Bean
    public Consumer<Server> statusReporter(MetricRegistry metrics) {
        return server -> {
            server.setRequestLog(new StatusCodeMetrics(server.getRequestLog(), metrics, PREFIX));
        };
    }

    @Bean
    public Function<Handler, Handler> getHandlerCustomizer(final MetricRegistry metrics) {
        return handler -> {
            final InstrumentedHandler instrumented = new InstrumentedHandler(metrics, PREFIX);
            instrumented.setHandler(handler);
            return instrumented;
        };
    }

    static class OTQueuedThreadPool extends InstrumentedQueuedThreadPool {
        OTQueuedThreadPool(MetricRegistry metricRegistry, int qSize) {
            super(metricRegistry,
                32, 32, // Default number of threads, overridden in otj-server EmbeddedJetty
                30000,  // Idle timeout, irrelevant since max == min
                new BlockingArrayQueue<>(qSize, // Initial queue size
                    8, // Expand increment (irrelevant; initial == max)
                    qSize)); // Upper bound on work queue
        }

        @Override
        protected void runJob(Runnable job) {
            try {
                job.run();
            } finally {
                MDC.remove(ConservedHeader.REQUEST_ID.getLogName());
            }
        }
    }
}
