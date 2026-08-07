`package com.acme.oneline; import java.util.List;` on ONE line is legal Java.
Requiring the whole line to BE the declaration sent it to the type check, which
declared it the default package — so this directory became its own source root
and the class landed in the wrong package. In its own tree, because a sibling's
root would otherwise cover for it.
