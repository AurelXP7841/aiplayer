package dev.aiplayer;

/** Une action = une petite machine à états exécutée tick par tick. */
public abstract class Action {
    public enum Status { RUNNING, DONE, FAILED }

    public String failReason = "";

    /** true si l'action peut tourner avec un écran (inventaire/coffre) ouvert. */
    public boolean allowsScreen() { return false; }

    public void start(Controller c) {}
    public void stop(Controller c) {}
    public abstract Status tick(Controller c);
    public abstract String describe();

    protected Status fail(String reason) {
        this.failReason = reason;
        return Status.FAILED;
    }
}
