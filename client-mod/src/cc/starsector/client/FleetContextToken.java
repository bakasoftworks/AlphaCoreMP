package cc.starsector.client;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;

/**
 * W4 all-skills gate-2 vehicle, CLIENT end: the fail-closed
 * {@link CampaignFleetAPI} token installed on a union-sheeted commander's stats so the engine's
 * commander-skill throttle sees a non-null fleet
 * and runs its adapts-to-the-fleet scaling instead of short-circuiting to maxBonus. TYPED twin
 * of the server's {@code AllSkillsFleetData.FleetTokenHandler} Proxy: java.lang.reflect is
 * classloader-B-DENIED in the mod jar, and a typed stub is the ONLY vehicle — the
 * engine's sole implementor {@code CampaignFleet} is campaign-bound and its fleetData field is
 * ctor-assigned with no setter, so no engine fleet can ever answer
 * {@code getFleetData()} with our fab.
 *
 * <p>Semantics mirror the server handler EXACTLY: {@code getFleetData()} answers with the REAL
 * fab; {@code toString} identifies the token; EVERY other engine read throws naming the method —
 * the granted family's real reads all go through {@code getFleetData()},
 *
 * so an unmapped read must surface as a crash naming the path,
 * never a faked value. {@code equals}/{@code hashCode} stay {@code Object} identity (the
 * server Proxy's semantics).
 */
public final class FleetContextToken implements CampaignFleetAPI {

    private final FleetDataAPI fleetData;

    public FleetContextToken(FleetDataAPI fleetData) {
        if (fleetData == null) {
            throw new IllegalStateException("cc-allskills fleet token: null fleet data at ctor"
                    + " — fail-closed, a data-less token re-mints the throttle short-circuit defect");
        }
        this.fleetData = fleetData;
    }

    /** Gate 2's one REAL answer — the engine throttle's sole read through this token. */
    @Override
    public FleetDataAPI getFleetData() {
        return fleetData;
    }

    @Override
    public String toString() {
        return "cc-allskills-fleet-token";
    }

    private static IllegalStateException unmapped(String name) {
        return new IllegalStateException("cc-allskills fleet token: unexpected engine read '"
                + name + "' — map this path and extend the token with the REAL answer"
                + " (never a fake)");
    }

    // ------------------------------------------------------------------------------------
    // GENERATED fail-closed surface — 234 throwing methods emitted by the stub generator
    // against 0.98a-RC8 (235 instance methods = 105 CampaignFleetAPI + 115 SectorEntityToken
    // + 15 FleetOrStubAPI; getFleetData carved out above). NEVER hand-edit below: on a game
    // update re-run `GenStub emit` (recipe in its header) and replace the block wholesale.
    // ------------------------------------------------------------------------------------
    @Override public void deflate() { throw unmapped("deflate"); }
    @Override public void setLocation(float a0, float a1) { throw unmapped("setLocation"); }
    @Override public java.lang.String getName() { throw unmapped("getName"); }
    @Override public boolean isHidden() { throw unmapped("isHidden"); }
    @Override public boolean isEmpty() { throw unmapped("isEmpty"); }
    @Override public org.lwjgl.util.vector.Vector2f getLocation() { throw unmapped("getLocation"); }
    @Override public void setName(java.lang.String a0) { throw unmapped("setName"); }
    @Override public com.fs.starfarer.api.campaign.FleetInflater getInflater() { throw unmapped("getInflater"); }
    @Override public int getNumDestroyers() { throw unmapped("getNumDestroyers"); }
    @Override public int getNumFighters() { throw unmapped("getNumFighters"); }
    @Override public org.lwjgl.util.vector.Vector2f getVelocity() { throw unmapped("getVelocity"); }
    @Override public float getTravelSpeed() { throw unmapped("getTravelSpeed"); }
    @Override public int getFleetPoints() { throw unmapped("getFleetPoints"); }
    @Override public boolean isValidPlayerFleet() { throw unmapped("isValidPlayerFleet"); }
    @Override public boolean isInHyperspace() { throw unmapped("isInHyperspace"); }
    @Override public void setNoEngaging(float a0) { throw unmapped("setNoEngaging"); }
    @Override public void setMoveDestination(float a0, float a1) { throw unmapped("setMoveDestination"); }
    @Override public void addAssignment(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2, java.lang.String a3, com.fs.starfarer.api.Script a4) { throw unmapped("addAssignment"); }
    @Override public void addAssignment(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2, java.lang.String a3, boolean a4, com.fs.starfarer.api.Script a5, com.fs.starfarer.api.Script a6) { throw unmapped("addAssignment"); }
    @Override public void addAssignment(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2) { throw unmapped("addAssignment"); }
    @Override public void addAssignment(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2, java.lang.String a3) { throw unmapped("addAssignment"); }
    @Override public void addAssignment(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2, com.fs.starfarer.api.Script a3) { throw unmapped("addAssignment"); }
    @Override public int getFleetSizeCount() { throw unmapped("getFleetSizeCount"); }
    @Override public void setNoFactionInName(boolean a0) { throw unmapped("setNoFactionInName"); }
    @Override public boolean isNoFactionInName() { throw unmapped("isNoFactionInName"); }
    @Override public com.fs.starfarer.api.characters.MutableCharacterStatsAPI getCommanderStats() { throw unmapped("getCommanderStats"); }
    @Override public boolean isHostileTo(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("isHostileTo"); }
    @Override public int getNumCapitals() { throw unmapped("getNumCapitals"); }
    @Override public com.fs.starfarer.api.fleet.FleetMemberViewAPI getViewForMember(com.fs.starfarer.api.fleet.FleetMemberAPI a0) { throw unmapped("getViewForMember"); }
    @Override public void setVelocity(float a0, float a1) { throw unmapped("setVelocity"); }
    @Override public float getAcceleration() { throw unmapped("getAcceleration"); }
    @Override public void updateCounts() { throw unmapped("updateCounts"); }
    @Override public boolean isFriendlyTo(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("isFriendlyTo"); }
    @Override public boolean knowsWhoPlayerIs() { throw unmapped("knowsWhoPlayerIs"); }
    @Override public float getCurrBurnLevel() { throw unmapped("getCurrBurnLevel"); }
    @Override public java.lang.String getNameWithFaction() { throw unmapped("getNameWithFaction"); }
    @Override public void setCommander(com.fs.starfarer.api.characters.PersonAPI a0) { throw unmapped("setCommander"); }
    @Override public com.fs.starfarer.api.fleet.FleetLogisticsAPI getLogistics() { throw unmapped("getLogistics"); }
    @Override public com.fs.starfarer.api.characters.PersonAPI getCommander() { throw unmapped("getCommander"); }
    @Override public void clearAssignments() { throw unmapped("clearAssignments"); }
    @Override public com.fs.starfarer.api.fleet.FleetMemberAPI getFlagship() { throw unmapped("getFlagship"); }
    @Override public boolean isPlayerFleet() { throw unmapped("isPlayerFleet"); }
    @Override public int getNumCruisers() { throw unmapped("getNumCruisers"); }
    @Override public int getNumFrigates() { throw unmapped("getNumFrigates"); }
    @Override public void goSlowOneFrame() { throw unmapped("goSlowOneFrame"); }
    @Override public void goSlowOneFrame(boolean a0) { throw unmapped("goSlowOneFrame"); }
    @Override public boolean isStationMode() { throw unmapped("isStationMode"); }
    @Override public int getNumMembersFast() { throw unmapped("getNumMembersFast"); }
    @Override public void forceOutIndicator() { throw unmapped("forceOutIndicator"); }
    @Override public void inflateIfNeeded() { throw unmapped("inflateIfNeeded"); }
    @Override public void fadeInIndicator() { throw unmapped("fadeInIndicator"); }
    @Override public java.lang.Boolean isDoNotAdvanceAI() { throw unmapped("isDoNotAdvanceAI"); }
    @Override public void setInflated(java.lang.Boolean a0) { throw unmapped("setInflated"); }
    @Override public void updateFleetView() { throw unmapped("updateFleetView"); }
    @Override public void setDoNotAdvanceAI(java.lang.Boolean a0) { throw unmapped("setDoNotAdvanceAI"); }
    @Override public void setAbortDespawn(java.lang.Boolean a0) { throw unmapped("setAbortDespawn"); }
    @Override public boolean isDespawning() { throw unmapped("isDespawning"); }
    @Override public java.util.List<com.fs.starfarer.api.campaign.listeners.FleetEventListener> getEventListeners() { throw unmapped("getEventListeners"); }
    @Override public java.lang.Boolean getAbortDespawn() { throw unmapped("getAbortDespawn"); }
    @Override public void fadeOutIndicator() { throw unmapped("fadeOutIndicator"); }
    @Override public boolean getGoSlowStop() { throw unmapped("getGoSlowStop"); }
    @Override public void setNoAutoDespawn(java.lang.Boolean a0) { throw unmapped("setNoAutoDespawn"); }
    @Override public boolean wasSlowMoving() { throw unmapped("wasSlowMoving"); }
    @Override public void setStationMode(java.lang.Boolean a0) { throw unmapped("setStationMode"); }
    @Override public boolean getGoSlowOneFrame() { throw unmapped("getGoSlowOneFrame"); }
    @Override public org.lwjgl.util.vector.Vector2f getMoveDestination() { throw unmapped("getMoveDestination"); }
    @Override public void setInflater(com.fs.starfarer.api.campaign.FleetInflater a0) { throw unmapped("setInflater"); }
    @Override public java.lang.Boolean isNoAutoDespawn() { throw unmapped("isNoAutoDespawn"); }
    @Override public int getNumShips() { throw unmapped("getNumShips"); }
    @Override public java.lang.Boolean getForceNoSensorProfileUpdate() { throw unmapped("getForceNoSensorProfileUpdate"); }
    @Override public org.lwjgl.util.vector.Vector2f getVelocityFromMovementModule() { throw unmapped("getVelocityFromMovementModule"); }
    @Override public void setPreferredResupplyLocation(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("setPreferredResupplyLocation"); }
    @Override public void setForceNoSensorProfileUpdate(java.lang.Boolean a0) { throw unmapped("setForceNoSensorProfileUpdate"); }
    @Override public void despawn() { throw unmapped("despawn"); }
    @Override public void despawn(com.fs.starfarer.api.campaign.CampaignEventListener.FleetDespawnReason a0, java.lang.Object a1) { throw unmapped("despawn"); }
    @Override public boolean isAIMode() { throw unmapped("isAIMode"); }
    @Override public boolean isInflated() { throw unmapped("isInflated"); }
    @Override public com.fs.starfarer.api.fleet.MutableFleetStatsAPI getStats() { throw unmapped("getStats"); }
    @Override public void setBattle(com.fs.starfarer.api.campaign.BattleAPI a0) { throw unmapped("setBattle"); }
    @Override public com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI getAI() { throw unmapped("getAI"); }
    @Override public void setAI(com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI a0) { throw unmapped("setAI"); }
    @Override public java.util.List<com.fs.starfarer.api.fleet.FleetMemberViewAPI> getViews() { throw unmapped("getViews"); }
    @Override public void setFaction(java.lang.String a0, boolean a1) { throw unmapped("setFaction"); }
    @Override public void setHidden(java.lang.Boolean a0) { throw unmapped("setHidden"); }
    @Override public void forceSync() { throw unmapped("forceSync"); }
    @Override public void setAIMode(boolean a0) { throw unmapped("setAIMode"); }
    @Override public com.fs.starfarer.api.campaign.BattleAPI getBattle() { throw unmapped("getBattle"); }
    @Override public com.fs.starfarer.api.campaign.LocationAPI getContainingLocation() { throw unmapped("getContainingLocation"); }
    @Override public java.util.List<com.fs.starfarer.api.fleet.FleetMemberAPI> getMembersWithFightersCopy() { throw unmapped("getMembersWithFightersCopy"); }
    @Override public void setNullAIActionText(java.lang.String a0) { throw unmapped("setNullAIActionText"); }
    @Override public void setWasMousedOverByPlayer(java.lang.Boolean a0) { throw unmapped("setWasMousedOverByPlayer"); }
    @Override public float getEffectiveStrength() { throw unmapped("getEffectiveStrength"); }
    @Override public boolean hasShipsWithUniqueSig() { throw unmapped("hasShipsWithUniqueSig"); }
    @Override public boolean isInCurrentLocation() { throw unmapped("isInCurrentLocation"); }
    @Override public com.fs.starfarer.api.campaign.SectorEntityToken getInteractionTarget() { throw unmapped("getInteractionTarget"); }
    @Override public java.lang.String getNullAIActionText() { throw unmapped("getNullAIActionText"); }
    @Override public float getTotalSupplyCostPerDay() { throw unmapped("getTotalSupplyCostPerDay"); }
    @Override public void setMoveDestinationOverride(float a0, float a1) { throw unmapped("setMoveDestinationOverride"); }
    @Override public void setInteractionTarget(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("setInteractionTarget"); }
    @Override public boolean isInHyperspaceTransition() { throw unmapped("isInHyperspaceTransition"); }
    @Override public java.lang.String getNameWithFactionKeepCase() { throw unmapped("getNameWithFactionKeepCase"); }
    @Override public float getBaseSensorRangeToDetect(float a0) { throw unmapped("getBaseSensorRangeToDetect"); }
    @Override public java.lang.Boolean wasMousedOverByPlayer() { throw unmapped("wasMousedOverByPlayer"); }
    @Override public void removeFleetMemberWithDestructionFlash(com.fs.starfarer.api.fleet.FleetMemberAPI a0) { throw unmapped("removeFleetMemberWithDestructionFlash"); }
    @Override public boolean hasTag(java.lang.String a0) { throw unmapped("hasTag"); }
    @Override public boolean isAlive() { throw unmapped("isAlive"); }
    @Override public java.lang.String getId() { throw unmapped("getId"); }
    @Override public void setMemory(com.fs.starfarer.api.campaign.rules.MemoryAPI a0) { throw unmapped("setMemory"); }
    @Override public void advance(float a0) { throw unmapped("advance"); }
    @Override public java.lang.String getFullName() { throw unmapped("getFullName"); }
    @Override public void setFixedLocation(float a0, float a1) { throw unmapped("setFixedLocation"); }
    @Override public void setDiscoverable(java.lang.Boolean a0) { throw unmapped("setDiscoverable"); }
    @Override public boolean isTransponderOn() { throw unmapped("isTransponderOn"); }
    @Override public void addDropValue(java.lang.String a0, int a1) { throw unmapped("addDropValue"); }
    @Override public void addDropValue(com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData a0) { throw unmapped("addDropValue"); }
    @Override public java.lang.Float getDiscoveryXP() { throw unmapped("getDiscoveryXP"); }
    @Override public void setSensorProfile(java.lang.Float a0) { throw unmapped("setSensorProfile"); }
    @Override public void setDiscoveryXP(java.lang.Float a0) { throw unmapped("setDiscoveryXP"); }
    @Override public void setActivePerson(com.fs.starfarer.api.characters.PersonAPI a0) { throw unmapped("setActivePerson"); }
    @Override public boolean hasDiscoveryXP() { throw unmapped("hasDiscoveryXP"); }
    @Override public boolean hasScriptOfClass(java.lang.Class a0) { throw unmapped("hasScriptOfClass"); }
    @Override public void clearAbilities() { throw unmapped("clearAbilities"); }
    @Override public com.fs.starfarer.api.impl.campaign.procgen.Constellation getConstellation() { throw unmapped("getConstellation"); }
    @Override public java.awt.Color getIndicatorColor() { throw unmapped("getIndicatorColor"); }
    @Override public void removeScript(com.fs.starfarer.api.EveryFrameScript a0) { throw unmapped("removeScript"); }
    @Override public void removeAbility(java.lang.String a0) { throw unmapped("removeAbility"); }
    @Override public void setCircularOrbit(com.fs.starfarer.api.campaign.SectorEntityToken a0, float a1, float a2, float a3) { throw unmapped("setCircularOrbit"); }
    @Override public java.util.Map<java.lang.String, com.fs.starfarer.api.characters.AbilityPlugin> getAbilities() { throw unmapped("getAbilities"); }
    @Override public boolean hasSensorStrength() { throw unmapped("hasSensorStrength"); }
    @Override public void addDropRandom(java.lang.String a0, int a1, int a2) { throw unmapped("addDropRandom"); }
    @Override public void addDropRandom(com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData a0) { throw unmapped("addDropRandom"); }
    @Override public void addDropRandom(java.lang.String a0, int a1) { throw unmapped("addDropRandom"); }
    @Override public com.fs.starfarer.api.campaign.SectorEntityToken getOrbitFocus() { throw unmapped("getOrbitFocus"); }
    @Override public float getSensorStrength() { throw unmapped("getSensorStrength"); }
    @Override public void addFloatingText(java.lang.String a0, java.awt.Color a1, float a2) { throw unmapped("addFloatingText"); }
    @Override public void addFloatingText(java.lang.String a0, java.awt.Color a1, float a2, boolean a3) { throw unmapped("addFloatingText"); }
    @Override public java.util.Map<java.lang.String, java.lang.Object> getCustomData() { throw unmapped("getCustomData"); }
    @Override public java.awt.Color getLightColor() { throw unmapped("getLightColor"); }
    @Override public boolean isFreeTransfer() { throw unmapped("isFreeTransfer"); }
    @Override public boolean hasSensorProfile() { throw unmapped("hasSensorProfile"); }
    @Override public com.fs.starfarer.api.campaign.SectorEntityToken getLightSource() { throw unmapped("getLightSource"); }
    @Override public float getSensorProfile() { throw unmapped("getSensorProfile"); }
    @Override public java.util.List<com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData> getDropValue() { throw unmapped("getDropValue"); }
    @Override public boolean isDiscoverable() { throw unmapped("isDiscoverable"); }
    @Override public boolean isInOrNearSystem(com.fs.starfarer.api.campaign.StarSystemAPI a0) { throw unmapped("isInOrNearSystem"); }
    @Override public void setSensorStrength(java.lang.Float a0) { throw unmapped("setSensorStrength"); }
    @Override public com.fs.starfarer.api.campaign.CustomCampaignEntityPlugin getCustomPlugin() { throw unmapped("getCustomPlugin"); }
    @Override public com.fs.starfarer.api.characters.PersonAPI getActivePerson() { throw unmapped("getActivePerson"); }
    @Override public void setTransponderOn(boolean a0) { throw unmapped("setTransponderOn"); }
    @Override public java.util.List<com.fs.starfarer.api.impl.campaign.procgen.SalvageEntityGenDataSpec.DropData> getDropRandom() { throw unmapped("getDropRandom"); }
    @Override public com.fs.starfarer.api.combat.StatBonus getSensorRangeMod() { throw unmapped("getSensorRangeMod"); }
    @Override public void setFreeTransfer(boolean a0) { throw unmapped("setFreeTransfer"); }
    @Override public java.lang.Float getSalvageXP() { throw unmapped("getSalvageXP"); }
    @Override public void setLightSource(com.fs.starfarer.api.campaign.SectorEntityToken a0, java.awt.Color a1) { throw unmapped("setLightSource"); }
    @Override public void setOrbitFocus(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("setOrbitFocus"); }
    @Override public boolean isSystemCenter() { throw unmapped("isSystemCenter"); }
    @Override public boolean hasSalvageXP() { throw unmapped("hasSalvageXP"); }
    @Override public void clearFloatingText() { throw unmapped("clearFloatingText"); }
    @Override public void setSalvageXP(java.lang.Float a0) { throw unmapped("setSalvageXP"); }
    @Override public com.fs.starfarer.api.campaign.StarSystemAPI getStarSystem() { throw unmapped("getStarSystem"); }
    @Override public java.lang.Boolean getAlwaysUseSensorFaderBrightness() { throw unmapped("getAlwaysUseSensorFaderBrightness"); }
    @Override public void forceSensorContactFaderBrightness(float a0) { throw unmapped("forceSensorContactFaderBrightness"); }
    @Override public com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel getVisibilityLevelOfPlayerFleet() { throw unmapped("getVisibilityLevelOfPlayerFleet"); }
    @Override public com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel getVisibilityLevelToPlayerFleet() { throw unmapped("getVisibilityLevelToPlayerFleet"); }
    @Override public void setCircularOrbitPointingDown(com.fs.starfarer.api.campaign.SectorEntityToken a0, float a1, float a2, float a3) { throw unmapped("setCircularOrbitPointingDown"); }
    @Override public void setAutogenJumpPointNameInHyper(java.lang.String a0) { throw unmapped("setAutogenJumpPointNameInHyper"); }
    @Override public float getSensorContactFaderBrightness() { throw unmapped("getSensorContactFaderBrightness"); }
    @Override public void setAlwaysUseSensorFaderBrightness(java.lang.Boolean a0) { throw unmapped("setAlwaysUseSensorFaderBrightness"); }
    @Override public java.lang.String getAutogenJumpPointNameInHyper() { throw unmapped("getAutogenJumpPointNameInHyper"); }
    @Override public java.util.Collection<java.lang.String> getTags() { throw unmapped("getTags"); }
    @Override public void clearTags() { throw unmapped("clearTags"); }
    @Override public void addScript(com.fs.starfarer.api.EveryFrameScript a0) { throw unmapped("addScript"); }
    @Override public com.fs.starfarer.api.characters.AbilityPlugin getAbility(java.lang.String a0) { throw unmapped("getAbility"); }
    @Override public boolean hasAbility(java.lang.String a0) { throw unmapped("hasAbility"); }
    @Override public void setExpired(boolean a0) { throw unmapped("setExpired"); }
    @Override public boolean isStar() { throw unmapped("isStar"); }
    @Override public void setMarket(com.fs.starfarer.api.campaign.econ.MarketAPI a0) { throw unmapped("setMarket"); }
    @Override public com.fs.starfarer.api.campaign.rules.MemoryAPI getMemory() { throw unmapped("getMemory"); }
    @Override public void setOrbit(com.fs.starfarer.api.campaign.OrbitAPI a0) { throw unmapped("setOrbit"); }
    @Override public void removeTag(java.lang.String a0) { throw unmapped("removeTag"); }
    @Override public void addAbility(java.lang.String a0) { throw unmapped("addAbility"); }
    @Override public void setId(java.lang.String a0) { throw unmapped("setId"); }
    @Override public java.util.List<com.fs.starfarer.api.EveryFrameScript> getScripts() { throw unmapped("getScripts"); }
    @Override public com.fs.starfarer.api.campaign.FactionAPI getFaction() { throw unmapped("getFaction"); }
    @Override public com.fs.starfarer.api.campaign.OrbitAPI getOrbit() { throw unmapped("getOrbit"); }
    @Override public com.fs.starfarer.api.campaign.CargoAPI getCargo() { throw unmapped("getCargo"); }
    @Override public float getFacing() { throw unmapped("getFacing"); }
    @Override public void setFacing(float a0) { throw unmapped("setFacing"); }
    @Override public boolean isExpired() { throw unmapped("isExpired"); }
    @Override public void setFaction(java.lang.String a0) { throw unmapped("setFaction"); }
    @Override public float getRadius() { throw unmapped("getRadius"); }
    @Override public com.fs.starfarer.api.campaign.econ.MarketAPI getMarket() { throw unmapped("getMarket"); }
    @Override public void addTag(java.lang.String a0) { throw unmapped("addTag"); }
    @Override public org.lwjgl.util.vector.Vector2f getLocationInHyperspace() { throw unmapped("getLocationInHyperspace"); }
    @Override public java.lang.String getCustomDescriptionId() { throw unmapped("getCustomDescriptionId"); }
    @Override public void setCustomDescriptionId(java.lang.String a0) { throw unmapped("setCustomDescriptionId"); }
    @Override public void setCircularOrbitWithSpin(com.fs.starfarer.api.campaign.SectorEntityToken a0, float a1, float a2, float a3, float a4, float a5) { throw unmapped("setCircularOrbitWithSpin"); }
    @Override public com.fs.starfarer.api.campaign.CustomEntitySpecAPI getCustomEntitySpec() { throw unmapped("getCustomEntitySpec"); }
    @Override public void setExtendedDetectedAtRange(java.lang.Float a0) { throw unmapped("setExtendedDetectedAtRange"); }
    @Override public void forceSensorFaderBrightness(float a0) { throw unmapped("forceSensorFaderBrightness"); }
    @Override public void forceSensorFaderOut() { throw unmapped("forceSensorFaderOut"); }
    @Override public com.fs.starfarer.api.campaign.rules.MemoryAPI getMemoryWithoutUpdate() { throw unmapped("getMemoryWithoutUpdate"); }
    @Override public void setSkipForJumpPointAutoGen(boolean a0) { throw unmapped("setSkipForJumpPointAutoGen"); }
    @Override public void removeScriptsOfClass(java.lang.Class a0) { throw unmapped("removeScriptsOfClass"); }
    @Override public float getCircularOrbitAngle() { throw unmapped("getCircularOrbitAngle"); }
    @Override public boolean isVisibleToPlayerFleet() { throw unmapped("isVisibleToPlayerFleet"); }
    @Override public boolean isVisibleToSensorsOf(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("isVisibleToSensorsOf"); }
    @Override public float getMaxSensorRangeToDetect(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("getMaxSensorRangeToDetect"); }
    @Override public void setCircularOrbitAngle(float a0) { throw unmapped("setCircularOrbitAngle"); }
    @Override public float getCircularOrbitRadius() { throw unmapped("getCircularOrbitRadius"); }
    @Override public float getCircularOrbitPeriod() { throw unmapped("getCircularOrbitPeriod"); }
    @Override public float getExtendedDetectedAtRange() { throw unmapped("getExtendedDetectedAtRange"); }
    @Override public float getSensorFaderBrightness() { throw unmapped("getSensorFaderBrightness"); }
    @Override public boolean isSkipForJumpPointAutoGen() { throw unmapped("isSkipForJumpPointAutoGen"); }
    @Override public com.fs.starfarer.api.combat.StatBonus getDetectedRangeMod() { throw unmapped("getDetectedRangeMod"); }
    @Override public com.fs.starfarer.api.campaign.SectorEntityToken.VisibilityLevel getVisibilityLevelTo(com.fs.starfarer.api.campaign.SectorEntityToken a0) { throw unmapped("getVisibilityLevelTo"); }
    @Override public void setInteractionImage(java.lang.String a0, java.lang.String a1) { throw unmapped("setInteractionImage"); }
    @Override public void setContainingLocation(com.fs.starfarer.api.campaign.LocationAPI a0) { throw unmapped("setContainingLocation"); }
    @Override public java.lang.String getCustomEntityType() { throw unmapped("getCustomEntityType"); }
    @Override public void autoUpdateHyperLocationBasedOnInSystemEntityAtRadius(com.fs.starfarer.api.campaign.SectorEntityToken a0, float a1) { throw unmapped("autoUpdateHyperLocationBasedOnInSystemEntityAtRadius"); }
    @Override public java.lang.Float getDetectionRangeDetailsOverrideMult() { throw unmapped("getDetectionRangeDetailsOverrideMult"); }
    @Override public com.fs.starfarer.api.InteractionDialogImageVisual getCustomInteractionDialogImageVisual() { throw unmapped("getCustomInteractionDialogImageVisual"); }
    @Override public void setCustomInteractionDialogImageVisual(com.fs.starfarer.api.InteractionDialogImageVisual a0) { throw unmapped("setCustomInteractionDialogImageVisual"); }
    @Override public void setDetectionRangeDetailsOverrideMult(java.lang.Float a0) { throw unmapped("setDetectionRangeDetailsOverrideMult"); }
    @Override public void addEventListener(com.fs.starfarer.api.campaign.listeners.FleetEventListener a0) { throw unmapped("addEventListener"); }
    @Override public java.util.List<com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI> getAssignmentsCopy() { throw unmapped("getAssignmentsCopy"); }
    @Override public void removeFirstAssignmentIfItIs(com.fs.starfarer.api.campaign.FleetAssignment a0) { throw unmapped("removeFirstAssignmentIfItIs"); }
    @Override public boolean isFleet() { throw unmapped("isFleet"); }
    @Override public boolean isCurrentAssignment(com.fs.starfarer.api.campaign.FleetAssignment a0) { throw unmapped("isCurrentAssignment"); }
    @Override public com.fs.starfarer.api.campaign.ai.FleetAssignmentDataAPI getCurrentAssignment() { throw unmapped("getCurrentAssignment"); }
    @Override public void removeFirstAssignment() { throw unmapped("removeFirstAssignment"); }
    @Override public void addAssignmentAtStart(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2, java.lang.String a3, com.fs.starfarer.api.Script a4) { throw unmapped("addAssignmentAtStart"); }
    @Override public void addAssignmentAtStart(com.fs.starfarer.api.campaign.FleetAssignment a0, com.fs.starfarer.api.campaign.SectorEntityToken a1, float a2, com.fs.starfarer.api.Script a3) { throw unmapped("addAssignmentAtStart"); }
    @Override public void removeEventListener(com.fs.starfarer.api.campaign.listeners.FleetEventListener a0) { throw unmapped("removeEventListener"); }
}
