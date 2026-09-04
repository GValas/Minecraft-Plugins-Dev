package kguard;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Etat suivi pour un joueur connecte. Tout est manipule depuis le thread principal. */
public final class Data {

    public final UUID id;
    public final String name;

    // Horodatages d'exemption
    public long joinTime = System.currentTimeMillis();
    public long lastTeleport;
    public long lastVelocity;
    public long lastDamage;
    public long lastRespawn;
    public boolean exempt;              // exemption manuelle (/kguard exempt)

    // --- Mouvement ---
    public Location lastTo;
    public double lastDy;
    public int airTicks;
    public int ascendTicks;
    public int hoverTicks;
    public int gravityTicks;
    public int jesusTicks;
    public int noSlowTicks;
    public int groundSpoofTicks;
    public int phaseTicks;
    public int speedTicks;
    public int climbTicks;
    public double airMaxY;
    public Location lastSafe;
    public int lastJumpTick = -1000;
    public final Deque<Long> moves = new ArrayDeque<>();
    public final double[] speedSamples = new double[20];
    public int speedIndex;
    public int speedFilled;

    // Suivi du knockback (velocity)
    public Vector kbExpected;
    public int kbTicks = -1;
    public double kbMovedH;
    public double kbMovedY;

    // --- Combat ---
    public long lastSwingMs;
    public int lastSwingTick = -1000;
    public final Deque<Long> swings = new ArrayDeque<>();
    public final long[] clickGaps = new long[20];
    public int clickIndex;
    public int clickFilled;
    public UUID lastTarget;
    public long lastTargetMs;
    public long lastAttackMs;
    public int lastAttackTick = -1000;
    public final Deque<Double> reaches = new ArrayDeque<>();
    public int critStreak;

    // --- Blocs / objets ---
    public final Deque<Long> breaks = new ArrayDeque<>();
    public final Deque<Long> places = new ArrayDeque<>();
    public final Deque<Long> uses = new ArrayDeque<>();
    public Location lastBreak;
    public long lastBreakMs;
    public int minedDeep;               // blocs casses sous la limite de profondeur
    public int minedOre;                // minerais precieux dans ce total
    public int xrayReported;

    // --- Divers ---
    public String lastMessage = "";
    public long lastMessageMs;
    public int messageRepeats;
    public long inventoryOpenMs;
    public double inventoryMoved;
    public int inventoryMoves;

    // --- Violations ---
    public final Map<String, Double> vl = new HashMap<>();
    public final Map<String, Long> lastAlert = new HashMap<>();

    public Data(UUID id, String name) {
        this.id = id;
        this.name = name;
    }

    public double vl(String check) {
        return vl.getOrDefault(check, 0.0);
    }

    public double addVl(String check, double amount) {
        double v = vl(check) + amount;
        vl.put(check, v);
        return v;
    }

    public double totalVl() {
        double t = 0;
        for (double v : vl.values()) t += v;
        return t;
    }

    /** Moyenne glissante de la vitesse horizontale (1 s de deplacement). */
    public void pushSpeed(double d) {
        speedSamples[speedIndex] = d;
        speedIndex = (speedIndex + 1) % speedSamples.length;
        if (speedFilled < speedSamples.length) speedFilled++;
    }

    public double avgSpeed() {
        if (speedFilled == 0) return 0;
        double t = 0;
        for (int i = 0; i < speedFilled; i++) t += speedSamples[i];
        return t / speedFilled;
    }

    public void resetSpeed() {
        speedFilled = 0;
        speedIndex = 0;
    }

    public void pushClickGap(long gap) {
        clickGaps[clickIndex] = gap;
        clickIndex = (clickIndex + 1) % clickGaps.length;
        if (clickFilled < clickGaps.length) clickFilled++;
    }

    /** Ecart-type des intervalles entre clics, en ms (0 si pas assez d'echantillons). */
    public double clickDeviation() {
        if (clickFilled < clickGaps.length) return -1;
        double mean = 0;
        for (long g : clickGaps) mean += g;
        mean /= clickGaps.length;
        double var = 0;
        for (long g : clickGaps) var += (g - mean) * (g - mean);
        return Math.sqrt(var / clickGaps.length);
    }

    /** Retire les horodatages plus vieux que windowMs et renvoie ce qu'il reste. */
    public static int trim(Deque<Long> deque, long now, long windowMs) {
        while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) deque.pollFirst();
        return deque.size();
    }
}
