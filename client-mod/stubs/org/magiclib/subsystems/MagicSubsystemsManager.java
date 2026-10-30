package org.magiclib.subsystems;

import java.util.Collection;
import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * COMPILE-ONLY stub of MagicLib's subsystem manager, a Kotlin {@code object} (never jarred: see
 * {@link MagicSubsystem}'s javadoc for the build belt). The real class mixes the two Kotlin call shapes, and the stub
 * copies each verbatim from the pinned MagicLib 1.5.4 jar's own member list for
 * {@code org.magiclib.subsystems.MagicSubsystemsManager}:
 * <pre>
 *   public final class org.magiclib.subsystems.MagicSubsystemsManager {
 *     public static final org.magiclib.subsystems.MagicSubsystemsManager INSTANCE;
 *     public static final java.util.List&lt;org.magiclib.subsystems.MagicSubsystem&gt;
 *         getSubsystemsForShipCopy(com.fs.starfarer.api.combat.ShipAPI);            (a @JvmStatic: invokestatic)
 *     public final java.util.Collection&lt;org.magiclib.subsystems.MagicSubsystem&gt;
 *         sortSubsystems(java.util.Collection&lt;? extends org.magiclib.subsystems.MagicSubsystem&gt;);
 *                                                                 (an instance member: INSTANCE.sortSubsystems)
 *   }
 * </pre>
 * The two methods throw: a row that reaches them drove the manager wrapper instead of the pure core it should test.
 *
 */
public final class MagicSubsystemsManager {

    public static final MagicSubsystemsManager INSTANCE = new MagicSubsystemsManager();

    private MagicSubsystemsManager() {
    }

    public static final List<MagicSubsystem> getSubsystemsForShipCopy(ShipAPI ship) {
        throw new UnsupportedOperationException("compile-only MagicLib stub: getSubsystemsForShipCopy");
    }

    public final Collection<MagicSubsystem> sortSubsystems(Collection<? extends MagicSubsystem> subsystems) {
        throw new UnsupportedOperationException("compile-only MagicLib stub: sortSubsystems");
    }
}
