package org.jawata.spi.host;

/**
 * Whose turn it is to debug (Sprint 28k, contract §4.2, A3-2): the person's or the agent's, never both at once, and the person wins. A host
 * that cannot tell, such as the headless one, is always free: only the agent ever debugs there.
 */
public interface DebugTurn {

    /** The answer of a host with no debugger of its own: nobody else is debugging, so the agent may. */
    DebugTurn FREE = new DebugTurn() {
        @Override
        public boolean personIsDebugging() {
            return false;
        }

        @Override
        public void onPersonStartsDebugging(Runnable endAgentSessions) {
            // the person never starts debugging here, so there is nothing to end
        }
    };

    /** @return whether the person has a debug launch of their own running, in which case the agent does not start or attach */
    boolean personIsDebugging();

    /**
     * Asks to be told the moment the person starts debugging, so the agent's sessions end and the person has the program to themselves.
     *
     * @param endAgentSessions what the engine does then: close every session the agent holds
     */
    void onPersonStartsDebugging(Runnable endAgentSessions);
}
