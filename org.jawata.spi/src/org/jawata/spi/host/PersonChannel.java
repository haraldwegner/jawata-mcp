package org.jawata.spi.host;

/**
 * How the engine reaches the person using the host (Sprint 28k, contract §4.2): a notice to read, and a piece of long work they can see
 * going on and stop worrying about. It is the host's outlet for the engine's own state: the engine says what happened, and the host decides
 * how a person of that host would see it. The headless host writes both to its log; Eclipse shows a notice in the status bar and an
 * activity as a background job, so a screen is never taken over.
 *
 * <p>There is one such channel per host, and the engine has no other way of speaking to the person: a second notification mechanism is a
 * second form the contract refuses.</p>
 */
public interface PersonChannel {

    /** A channel that says nothing, for a host whose person cannot be reached. */
    PersonChannel SILENT = new PersonChannel() {
        @Override
        public void tell(Notice notice) {
            // nobody to tell
        }

        @Override
        public Activity begin(String label) {
            return new Activity() {
                @Override
                public void step(String what) {
                    // nobody to show it to
                }

                @Override
                public void close() {
                    // nothing was shown
                }
            };
        }
    };

    /**
     * Tells the person something.
     *
     * @param notice what to tell them
     */
    void tell(Notice notice);

    /**
     * Starts a piece of work the person can see, which lasts until the returned activity is closed.
     *
     * @param label what the work is, in words the person would use
     * @return the running activity; close it when the work is over
     */
    Activity begin(String label);

    /** One piece of long work, as the person sees it. */
    interface Activity extends AutoCloseable {

        /**
         * Says what the work is doing now.
         *
         * @param what the step, in words the person would use
         */
        void step(String what);

        /** Ends the activity; the person no longer sees it. */
        @Override
        void close();
    }
}
