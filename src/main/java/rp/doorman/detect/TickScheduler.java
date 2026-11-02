package rp.doorman.detect;

import rp.doorman.Doorman;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/** Minimal server-tick scheduler. {@link #run(int)} is called once per tick on the server thread. */
public final class TickScheduler {
	private record Task(int atTick, long seq, Runnable runnable) {
	}

	private final PriorityQueue<Task> queue = new PriorityQueue<>(
			(a, b) -> a.atTick != b.atTick ? Integer.compare(a.atTick, b.atTick) : Long.compare(a.seq, b.seq));
	private long seq;

	public synchronized void schedule(int atTick, Runnable runnable) {
		queue.add(new Task(atTick, seq++, runnable));
	}

	public void run(int now) {
		List<Runnable> due = new ArrayList<>();
		synchronized (this) {
			while (!queue.isEmpty() && queue.peek().atTick() <= now) {
				due.add(queue.poll().runnable());
			}
		}
		for (Runnable r : due) {
			try {
				r.run();
			} catch (RuntimeException e) {
				Doorman.LOGGER.error("Scheduled task failed", e);
			}
		}
	}

	public synchronized void clear() {
		queue.clear();
	}
}
