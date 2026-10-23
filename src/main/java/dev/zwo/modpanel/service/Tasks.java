package dev.zwo.modpanel.service;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Fila de trabalho fora da thread principal. O banco é sempre acessado daqui;
 * o que mexe na API do Bukkit volta para a thread principal com {@link #onMain}.
 */
public final class Tasks {

    @FunctionalInterface
    public interface Job<T> {
        T run() throws Exception;
    }

    private final JavaPlugin plugin;
    private final ExecutorService executor;
    private final Set<CompletableFuture<?>> waitingMain = ConcurrentHashMap.newKeySet();
    private volatile boolean closing;

    public Tasks(JavaPlugin plugin, int threads) {
        this.plugin = plugin;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ModPanel-Worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), factory);
    }

    public <T> CompletableFuture<T> async(Job<T> job) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(job.run());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /** Executa na thread principal e espera o resultado. Só pode ser chamado de um worker. */
    public <T> T onMain(Callable<T> work) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return work.call();
        }
        if (closing || !plugin.isEnabled()) {
            throw new IllegalStateException("O plugin está sendo desligado");
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        waitingMain.add(future);
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    future.complete(work.call());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("A thread principal não respondeu a tempo", e);
        } finally {
            waitingMain.remove(future);
        }
    }

    /** Agenda na thread principal sem esperar. */
    public void sync(Runnable work) {
        if (Bukkit.isPrimaryThread()) {
            work.run();
            return;
        }
        if (closing || !plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, work);
        } catch (RuntimeException e) {
            // plugin desativando entre a checagem e o agendamento
        }
    }

    /** Agenda para o próximo tick, mesmo se já estiver na thread principal. */
    public void nextTick(Runnable work) {
        if (closing || !plugin.isEnabled()) {
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, work);
        } catch (RuntimeException e) {
            // plugin desativando
        }
    }

    public Executor main() {
        return this::sync;
    }

    public void shutdown() {
        closing = true;
        for (CompletableFuture<?> future : waitingMain) {
            future.completeExceptionally(new IllegalStateException("O plugin está sendo desligado"));
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Algumas tarefas do banco não terminaram a tempo e foram interrompidas.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void log(String message, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, message, unwrap(error));
    }

    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
