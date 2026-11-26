package org.jawata.mcp.change;

import org.eclipse.core.resources.IFile;

/**
 * A change that writes a new file. It is no text change, since there is no old text, so it says itself what it would write and a preview
 * can show it (28h z155).
 */
public interface CreatesFile {

    /** The file the change would create. */
    IFile createdFile();

    /** The text it would write into it. */
    String createdContent();
}
