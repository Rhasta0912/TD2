package com.stellinova.tidewielder;

import java.util.UUID;

public class TidePlayerData {

    private final UUID id;

    private long maelstromReadyAt;
    private long bubbleReadyAt;
    private long tidepoolReadyAt;
    private long surgeReadyAt;
    private long typhoonReadyAt;

    private boolean inBubble;
    private long bubbleExpiresAt;

    private boolean inTyphoon;
    private long typhoonActiveUntil;
    private long typhoonNextBoltAt;

    public TidePlayerData(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }

    public long getMaelstromReadyAt() { return maelstromReadyAt; }
    public void setMaelstromReadyAt(long maelstromReadyAt) { this.maelstromReadyAt = maelstromReadyAt; }

    public long getBubbleReadyAt() { return bubbleReadyAt; }
    public void setBubbleReadyAt(long bubbleReadyAt) { this.bubbleReadyAt = bubbleReadyAt; }

    public long getTidepoolReadyAt() { return tidepoolReadyAt; }
    public void setTidepoolReadyAt(long tidepoolReadyAt) { this.tidepoolReadyAt = tidepoolReadyAt; }

    public long getSurgeReadyAt() { return surgeReadyAt; }
    public void setSurgeReadyAt(long surgeReadyAt) { this.surgeReadyAt = surgeReadyAt; }

    public long getTyphoonReadyAt() { return typhoonReadyAt; }
    public void setTyphoonReadyAt(long typhoonReadyAt) { this.typhoonReadyAt = typhoonReadyAt; }

    public boolean isInBubble() { return inBubble; }
    public void setInBubble(boolean inBubble) { this.inBubble = inBubble; }

    public long getBubbleExpiresAt() { return bubbleExpiresAt; }
    public void setBubbleExpiresAt(long bubbleExpiresAt) { this.bubbleExpiresAt = bubbleExpiresAt; }

    public boolean isInTyphoon() { return inTyphoon; }
    public void setInTyphoon(boolean inTyphoon) { this.inTyphoon = inTyphoon; }

    public long getTyphoonActiveUntil() { return typhoonActiveUntil; }
    public void setTyphoonActiveUntil(long typhoonActiveUntil) { this.typhoonActiveUntil = typhoonActiveUntil; }

    public long getTyphoonNextBoltAt() { return typhoonNextBoltAt; }
    public void setTyphoonNextBoltAt(long typhoonNextBoltAt) { this.typhoonNextBoltAt = typhoonNextBoltAt; }
}
