package org.magiclib.subsystems;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * COMPILE-ONLY stub of MagicLib's subsystem base class. The client build compiles it into its own
 * {@code build/stub-classes} tree, puts that tree on the mod and selftest javac classpaths, and FAILS if any
 * {@code org/magiclib/**} entry reaches the mod jar: the kit jar sits first in the game's script-jar loader, so a
 * jarred copy would shadow the real MagicLib class for every mod. At runtime the real class always resolves.
 *
 * <p>Only the members the kit's {@code cc.starsector.client.magic} package names are copied, each with the exact
 * descriptor of the pinned MagicLib 1.5.4 jar (its own member list for {@code org.magiclib.subsystems.MagicSubsystem}):
 * <pre>
 *   public abstract class org.magiclib.subsystems.MagicSubsystem {
 *     protected final com.fs.starfarer.api.combat.ShipAPI ship;
 *     protected org.magiclib.subsystems.MagicSubsystem$State state;
 *     public org.magiclib.subsystems.MagicSubsystem(com.fs.starfarer.api.combat.ShipAPI);
 *     public int getOrder();
 *     public boolean isToggle();
 *     public org.magiclib.subsystems.MagicSubsystem$State getState();
 *     public abstract java.lang.String getDisplayText();
 *   }
 *   public final class org.magiclib.subsystems.MagicSubsystem$State extends java.lang.Enum {
 *     READY, IN, ACTIVE, OUT, COOLDOWN   (declaration order verbatim)
 *   }
 * </pre>
 * Bodies are trivial and never throw, so the client rows can subclass this stub and drive the magic package's
 * pure cores without MagicLib on the path. {@code getOrder()} / {@code isToggle()} return fixed stand-in values
 * (the rows override them); the real defaults are MagicLib's.
 *
 */
public abstract class MagicSubsystem {

    /** The real class's {@code protected final} owner field (no getter in MagicLib). */
    protected final ShipAPI ship;
    /** The real class's {@code protected} state field; the stub starts READY like a fresh instance. */
    protected State state = State.READY;

    public MagicSubsystem(ShipAPI ship) {
        this.ship = ship;
    }

    public int getOrder() {
        return 0;
    }

    public boolean isToggle() {
        return false;
    }

    public State getState() {
        return state;
    }

    public abstract String getDisplayText();

    /** MagicLib's subsystem state machine, values in the real declaration order. */
    public enum State {
        READY, IN, ACTIVE, OUT, COOLDOWN
    }
}
