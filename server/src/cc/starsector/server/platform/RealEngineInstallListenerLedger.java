package cc.starsector.server.platform;

import cc.starsector.server.names.EngineSymContentInit;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The engine listeners a roster install's round adds, let go at the install's rollback
 * through the engine's own {@code removeListener}.
 *
 * <p><b>Why it exists.</b> The engine keeps ONE list of battle-event listeners (ship destroyed or disabled, wing
 * destroyed, member deployed or retreated).
 * Three engine objects put themselves on it during an install: each side's fleet manager,
 *each manager's transfer manager (in its own
 * constructor), and the wave admiral's deployment planner, which registers itself at the bottom of the
 * {@code AdmiralAI} constructor chain.
 * The windowed game removes none of them: a battle's end drops the whole engine, the list with
 * it. The serve keeps ONE engine across rounds and boot warm-up passes, so the
 * install's rollback stands in for that drop. It named the first two and never the third: every reset left one
 * planner on the list, and a planner holds both of its round's fleet managers, with every deployed record and Ship of
 * that round. A heap dump read 111 removed Ships under 8 stale managers, pinned by the 4 planners of the four boot
 * warm-up passes. A bastion carried that residue once; a round serve gained it every round.
 *
 * <p><b>What it does.</b> {@link #open} copies the list's identities before the install's first engine call.
 * {@link #letGoAdded} runs in the rollback, after the install's own named removals, and hands every listener that is
 * on the list and was not there at open to the engine's {@code removeListener}. The rule is the list, never the
 * admiral: whatever a round registers leaves with the round, a later game build's additions included.
 *
 * <p><b>Sim effect: none found.</b> A stale planner is never advanced (its manager is off the engine's manager
 * list). Its listener methods only add to its own two strength tallies, after a walk of its own manager's
 * all-ever-deployed set and with no random draw. Removing it frees that walk on every
 * ship death, and the heap.
 *
 * <p><b>Fail-soft.</b> A memory fix never blocks a reset: a resolve miss reads {@code OFF}, a throw is told in the
 * returned row, and the rollback's own fail-closed assertions run on unchanged. No knob. Nothing here rides a tick:
 * both calls run on the thread that installs and rolls back a roster.
 */
public final class RealEngineInstallListenerLedger {

    private final Object engine;

    /** The engine's listener-list field; null = OFF. */
    private final Field listField;

    /** Identity set of the listeners that were on the list at {@link #open}. */
    private final Set<Object> before;

    private final String state;

    private RealEngineInstallListenerLedger(Object engine, Field listField, Set<Object> before, String state) {
        this.engine = engine;
        this.listField = listField;
        this.before = before;
        this.state = state;
    }

    /** Open against the live engine: the list field is the engine-name table's row. Never throws. */
    public static RealEngineInstallListenerLedger open(Object engine) {
        try {
            return open(engine,
                    EngineSymContentInit.CONTENT_INIT_ENGINE_LISTENER_LIST_FIELD.field(engine.getClass()));
        } catch (Throwable t) {
            return off(engine, t);
        }
    }

    /**
     * {@link #open(Object)} against a named field (the contract rows hand in a stand-in's). A field that does not
     * hold a {@code List} reads {@code OFF}.
     */
    public static RealEngineInstallListenerLedger open(Object engine, Field listField) {
        try {
            listField.setAccessible(true);
            Object value = listField.get(engine);
            if (!(value instanceof List)) {
                throw new IllegalStateException("not a List: " + listField.getName());
            }
            Set<Object> before = Collections.newSetFromMap(new IdentityHashMap<>());
            before.addAll((List<?>) value);
            return new RealEngineInstallListenerLedger(engine, listField, before,
                    "armed listeners_before=" + before.size());
        } catch (Throwable t) {
            return off(engine, t);
        }
    }

    /** {@code armed listeners_before=<n>}, or {@code OFF <why>}. */
    public String state() {
        return state;
    }

    /**
     * The rollback's call, after the install's own named removals: every listener on the engine's list that was not
     * there at {@link #open} leaves through {@code removeListener}, newest first. Returns the rollback's log row:
     * {@code let_go=<n> left=<n> classes=<names>}, the {@code OFF} state, or {@code null} when nothing had been
     * added and nothing is left (no row is owed). Never throws.
     */
    public String letGoAdded(Method removeListener) {
        if (listField == null) {
            return state;
        }
        int letGo = 0;
        StringBuilder classes = new StringBuilder();
        try {
            // A COPY is walked: removeListener edits the list being read.
            List<Object> now = new ArrayList<>(liveList());
            for (int i = now.size() - 1; i >= 0; i--) {
                Object listener = now.get(i);
                if (before.contains(listener)) {
                    continue;
                }
                removeListener.invoke(engine, listener);
                letGo++;
                if (letGo <= 4) {
                    classes.append(letGo == 1 ? "" : ",")
                            .append(listener == null ? "null" : listener.getClass().getName());
                }
            }
            int left = 0;
            for (Object listener : liveList()) {
                if (!before.contains(listener)) {
                    left++;
                }
            }
            if (letGo == 0 && left == 0) {
                return null;
            }
            return "let_go=" + letGo + " left=" + left + (letGo == 0 ? "" : " classes=" + classes);
        } catch (Throwable t) {
            return "let_go=" + letGo + " STOPPED " + describe(t);
        }
    }

    private List<?> liveList() throws IllegalAccessException {
        Object value = listField.get(engine);
        if (!(value instanceof List)) {
            throw new IllegalStateException("the engine's listener list is gone");
        }
        return (List<?>) value;
    }

    private static RealEngineInstallListenerLedger off(Object engine, Throwable t) {
        return new RealEngineInstallListenerLedger(engine, null, Collections.emptySet(), "OFF " + describe(t));
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }
}
