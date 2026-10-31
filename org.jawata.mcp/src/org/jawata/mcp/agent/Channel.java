package org.jawata.mcp.agent;

/**
 * Which side a message to the agent came from (Sprint 28h, S8, D19). A message carries its channel as a field of the connection it
 * arrived on and never as a property of its text, so what a hired agent writes can never be taken for the person's word.
 */
public enum Channel {

    /** The person, through the host the agent serves. Only this channel may set or end a grant of autonomy. */
    HOST,

    /** A hired agent: a brain, or a subagent's report. It informs the loop and cannot change a grant. */
    BRAIN
}
