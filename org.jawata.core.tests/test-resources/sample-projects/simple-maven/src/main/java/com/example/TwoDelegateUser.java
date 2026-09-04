package com.example;

/** Calls both of TwoDelegateMiddleMan's forwarders, so each removal has a call site. */
public class TwoDelegateUser {

    public String describe(TwoDelegateMiddleMan middle) {
        return middle.manager() + ":" + middle.balance();
    }
}
