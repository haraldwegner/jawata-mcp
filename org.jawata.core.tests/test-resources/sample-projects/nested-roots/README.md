No build file of any kind, so the last-resort discovery walk runs. `code/` holds
a class declaring `package com.example`, and `code/com/example/util/` holds one
declaring NOTHING — which made the walk derive that directory as its own root,
INSIDE the root derived from its sibling. Overlapping roots, the same file
counted twice, and a phantom "declared package does not match" on legal code.
