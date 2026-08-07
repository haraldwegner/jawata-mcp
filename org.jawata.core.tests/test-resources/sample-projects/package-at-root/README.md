A file at the project root declaring `package com.example`. Stripping one parent
per segment walks ABOVE the project — a root outside the project, which then
sorted shallowest and suppressed everything. The derivation must never escape the
project it is loading.
