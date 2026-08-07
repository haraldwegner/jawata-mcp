A stray file at the project root that declares NO package, beside a real package
tree. The root-level file yields the project root itself as a candidate; de-nesting
then let that suppress `code/`, and com.example.Foo stopped resolving — a fix that
turned a cosmetic extra root into "loads with no usable roots and reports success".
