package dev.lea.claudebot;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/** Ereignisse (Chat, Schaden, Aufgaben) für Claude. Wird beim Abholen geleert. */
public class EventLog {
	private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
	private final Deque<String> pending = new ArrayDeque<>();

	public synchronized void add(String line) {
		pending.addLast("[" + LocalTime.now().format(FMT) + "] " + line);
		while (pending.size() > 300) pending.removeFirst();
		notifyAll();
	}

	/** Holt alle neuen Ereignisse ab (leer, wenn keine). */
	public synchronized String drain() {
		if (pending.isEmpty()) return "";
		StringBuilder sb = new StringBuilder();
		while (!pending.isEmpty()) sb.append(pending.removeFirst()).append('\n');
		return sb.toString();
	}

	/** Wartet bis zu ms Millisekunden auf neue Ereignisse. */
	public synchronized String await(long ms) {
		long end = System.currentTimeMillis() + ms;
		while (pending.isEmpty()) {
			long left = end - System.currentTimeMillis();
			if (left <= 0) break;
			try {
				wait(left);
			} catch (InterruptedException e) {
				break;
			}
		}
		return drain();
	}
}
