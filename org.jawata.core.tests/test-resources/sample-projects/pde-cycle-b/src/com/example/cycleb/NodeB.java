package com.example.cycleb;

import com.example.cyclea.NodeA;

/** The other half: B uses A while A requires B. */
public class NodeB {
    public String pair() {
        return new NodeA().name() + "b";
    }
}
