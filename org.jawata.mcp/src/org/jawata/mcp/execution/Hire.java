package org.jawata.mcp.execution;

import org.jawata.mcp.execution.HireRegistry.Registration;
import org.jawata.mcp.execution.HiredRun.Proposal;

/**
 * One hire's account (28h, design step 4): who hired whom, with what brief, the registration it ran under and what its run proposed. The tree of
 * these accounts is the hierarchy; what a lead is told about a hire is to be read from here.
 */
public class Hire {
    private String hirer;
    private String hired;
    private String brief;
    private Registration running;
    private Proposal proposal;

    public Hire() {
    }

    public String getHirer() {
        return hirer;
    }

    public void setHirer(String hirer) {
        this.hirer = hirer;
    }

    public String getHired() {
        return hired;
    }

    public void setHired(String hired) {
        this.hired = hired;
    }

    public String getBrief() {
        return brief;
    }

    public void setBrief(String brief) {
        this.brief = brief;
    }

    public Registration getRunning() {
        return running;
    }

    public void setRunning(Registration running) {
        this.running = running;
    }

    public Proposal getProposal() {
        return proposal;
    }

    public void setProposal(Proposal proposal) {
        this.proposal = proposal;
    }
}
