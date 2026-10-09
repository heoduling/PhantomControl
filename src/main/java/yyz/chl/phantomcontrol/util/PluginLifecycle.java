package yyz.chl.phantomcontrol.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Tracks this enable cycle's work until callbacks actually finish, not merely until a caller cancels. */
public final class PluginLifecycle {
    public static final class EntityRetiredException extends CancellationException {
        public EntityRetiredException() { super("玩家已经离开"); }
    }
    private final Plugin plugin;
    private final Set<Job<?>> pending = new HashSet<>();
    private boolean accepting = true;

    public PluginLifecycle(Plugin plugin) { this.plugin = plugin; }
    public synchronized boolean isAccepting() { return accepting; }
    public synchronized int pendingCount() { return pending.size(); }

    private synchronized <T> Job<T> admit(boolean cleanup) {
        if (!accepting && !cleanup) return null;
        Job<T> job = new Job<>(); pending.add(job); return job;
    }
    private final class Job<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final CompletableFuture<Void> settled = new CompletableFuture<>();
        void finish(T value, Throwable error) {
            if (error == null) result.complete(value); else result.completeExceptionally(error);
            synchronized (PluginLifecycle.this) { pending.remove(this); }
            settled.complete(null);
        }
        void run(Supplier<T> work, boolean cleanup) {
            try {
                if (!plugin.isEnabled() || (!cleanup && !isAccepting())) throw new CancellationException("插件正在停止");
                finish(work.get(), null);
            } catch (Throwable error) { finish(null, error); }
        }
    }
    public <T> T callIfRunning(Supplier<T> work, T rejected) {
        Job<T> job = admit(false);
        if (job == null) return rejected;
        try { return work.get(); }
        finally { job.finish(null, null); }
    }
    public <T> CompletableFuture<T> entity(Player player, Supplier<T> work) { return entity(player, work, false); }
    public <T> CompletableFuture<T> entity(Player player, Supplier<T> work, boolean cleanup) {
        Job<T> job = admit(cleanup);
        if (job == null) return CompletableFuture.failedFuture(new CancellationException("插件正在停止"));
        try {
            if (player.getScheduler().run(plugin, task -> job.run(work, cleanup),
                    () -> job.finish(null, new EntityRetiredException())) == null) {
                job.finish(null, new EntityRetiredException());
            }
        } catch (RuntimeException error) { job.finish(null, error); }
        return job.result;
    }
    public CompletableFuture<Void> entity(Player player, Runnable work) {
        return entity(player, () -> { work.run(); return null; });
    }
    public <T> CompletableFuture<T> async(Supplier<T> work) { return async(work, false); }
    public CompletableFuture<Void> global(Runnable work, boolean cleanup) {
        Job<Void> job = admit(cleanup);
        if (job == null) return CompletableFuture.failedFuture(new CancellationException("插件正在停止"));
        try { Bukkit.getGlobalRegionScheduler().execute(plugin, () -> job.run(() -> { work.run(); return null; }, cleanup)); }
        catch (RuntimeException error) { job.finish(null, error); }
        return job.result;
    }
    public <T> CompletableFuture<T> async(Supplier<T> work, boolean cleanup) {
        Job<T> job = admit(cleanup);
        if (job == null) return CompletableFuture.failedFuture(new CancellationException("插件正在停止"));
        try { Bukkit.getAsyncScheduler().runNow(plugin, task -> job.run(work, cleanup)); }
        catch (RuntimeException error) { job.finish(null, error); }
        return job.result;
    }
    public synchronized CompletableFuture<Void> stopAndDrain() {
        accepting = false;
        return CompletableFuture.allOf(pending.stream().map(j -> j.settled).toArray(CompletableFuture[]::new));
    }
    public void abort() {
        List<Job<?>> jobs;
        synchronized (this) { accepting = false; jobs = List.copyOf(pending); }
        jobs.forEach(j -> j.finish(null, new CancellationException("插件已停用")));
    }
}
