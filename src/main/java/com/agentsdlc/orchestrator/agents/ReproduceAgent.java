package com.agentsdlc.orchestrator.agents;

import com.agentsdlc.orchestrator.core.Agent;
import com.agentsdlc.orchestrator.core.TaskContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Reproduces the race on the unmodified legacy code with real fault
 * injection: the code's hook is set to a two-party barrier, which holds each
 * caller between the "is the alias free?" check and the write until both have
 * passed the check. That forces, every time, the interleaving production only
 * hits under load. No fix is attempted unless the bug is reproduced.
 *
 * <p>Outputs: {@code reproduced}, {@code winners}, {@code final.target}.</p>
 */
public final class ReproduceAgent implements Agent {

    /** Creates the agent. */
    public ReproduceAgent() {
        // Stateless.
    }

    @Override
    public void execute(TaskContext ctx) throws Exception {
        LegacyModule legacy = new LegacyModule(ctx.workDir());
        Path classes = legacy.compile(List.of(ctx.workDir().resolve(LegacyModule.REGISTRY)), "repro-classes");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> type = loader.loadClass("legacy.alias.AliasRegistry");
            Object registry = type.getDeclaredConstructor().newInstance();
            Method register = type.getMethod("register", String.class, String.class);
            Method resolve = type.getMethod("resolve", String.class);
            Field hook = type.getField("faultInjection");
            CyclicBarrier bothInside = new CyclicBarrier(2);
            hook.set(null, (Runnable) () -> {
                try {
                    bothInside.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                }
            });
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Object> a = pool.submit(() -> register.invoke(registry, "promo", "https://example.com/a"));
                Future<Object> b = pool.submit(() -> register.invoke(registry, "promo", "https://example.com/b"));
                int winners = ((Boolean) a.get(10, TimeUnit.SECONDS) ? 1 : 0) + ((Boolean) b.get(10, TimeUnit.SECONDS) ? 1 : 0);
                String finalTarget = (String) resolve.invoke(registry, "promo");
                boolean reproduced = winners > 1;
                ctx.put("reproduced", Boolean.toString(reproduced));
                ctx.put("winners", Integer.toString(winners));
                ctx.put("final.target", finalTarget);
                ctx.writeArtifact("REPRODUCTION.md", "# Reproduction\n\n- Fault injection: two-party barrier between "
                        + "the existence check and the write\n- Callers told they won the alias: " + winners
                        + " (expected 1)\n- Target left in the registry: " + finalTarget
                        + " (one caller's link was silently overwritten)\n- Reproduced: " + reproduced + "\n");
                if (!reproduced) {
                    throw new IllegalStateException("race not reproduced; refusing to fix an unconfirmed bug");
                }
                ctx.decide("race reproduced deterministically", winners + " callers won the same alias",
                        Map.of("winners", Integer.toString(winners)));
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
