# Catalogue ingest sample — read this before 187 rows are written

Generated 2026-08-30 from `patterns-22a34127d0b0.json`, content sha256 `759382352e8b`, fork pin `22a34127d0b0`.

**If that sha does not match the snapshot you are about to ingest, this sample is stale and must be regenerated.** An earlier version of this file was read-ready and wrong: it predated two fixes and showed `cause — (none)` on every row.

**Why you are reading this.** These rows become recall entries. This loader has produced entries whose 'symptom' was really a section heading, so a person reads a sample before anything is written in bulk.

**What each field is for.** *situation* is what a recall matches on. *principle* is what you see in a result. *cause* is what separates two patterns that share a situation — Factory and Builder both answer "constructing an object".

**Checked mechanically across all 187 rows first, not just this sample:** 0 heading-shaped summaries · 0 heading-shaped situations · 187/187 carry a family · 187/187 carry tags · 187/187 carry a cause · 186/187 carry an entry point (naked-objects has no Java source at this pin).

One pattern per family, largest family first.

---

## Behavioral — 41 patterns

**`acyclic-visitor`**

- **situation** — when a new operation must be added over an existing type hierarchy without modifying it and without every visitor depending on every type
- **cause** — adding an operation to a hierarchy either modifies every class or makes every visitor depend on every type, and both couplings grow quadratically
- **principle** — The Acyclic Visitor pattern in Java decouples operations from an object hierarchy, providing a flexible design for various applications.
- **tags** — Decoupling, Extensibility, Interface, Object composition
- **entry point** — `com.iluwatar.acyclicvisitor.App`

## Structural — 35 patterns

**`abstract-document`**

- **situation** — when objects of one family share some attributes and each type adds its own, and new properties must be addable without changing the classes that read them
- **cause** — readers are compiled against fixed classes, so every new property forces a change in every class that reads it
- **principle** — The Abstract Document design pattern in Java is a crucial structural design pattern that provides a consistent way to handle hierarchical and tree-like data structures by defining a common interface for various document types.
- **tags** — Abstraction, Decoupling, Dynamic typing, Encapsulation, Extensibility, Polymorphism
- **entry point** — `com.iluwatar.abstractdocument.App`

## Architectural — 27 patterns

**`backends-for-frontends`**

- **situation** — when several client types need different shapes of the same data and one shared API is being distorted to serve them all
- **cause** — one general API must serve clients with conflicting needs, so it grows toward the union of all of them and fits none
- **principle** — Provide each client-side application (mobile, desktop, chatbot, and so on) with its own dedicated backend service, so every client gets an API shaped exactly for its own needs instead of sharing one general-purpose backend with every other client.
- **tags** — API design, Architecture, Client-server, Decoupling, Microservices
- **entry point** — `com.iluwatar.bff.App`

## Concurrency — 22 patterns

**`active-object`**

- **situation** — when an object's methods must run on its own thread so callers are never blocked by its work
- **cause** — method execution and method invocation share the caller's thread, so a slow method blocks every caller
- **principle** — The Active Object pattern provides a reliable method for asynchronous processing in Java, ensuring responsive applications and efficient thread management.
- **tags** — Asynchronous, Decoupling, Messaging, Synchronization, Thread management
- **entry point** — `com.iluwatar.activeobject.App`

## Creational — 14 patterns

**`abstract-factory`**

- **situation** — when a system must be configured with one of several families of related objects, and the families must not be mixed
- **cause** — creating members of a family one by one lets incompatible variants be mixed, and nothing enforces family consistency
- **principle** — The Abstract Factory pattern in Java provides an interface for creating families of related or dependent objects without specifying their concrete classes, enhancing modularity and flexibility in software design.
- **tags** — Abstraction, Decoupling, Gang of Four, Instantiation, Polymorphism
- **entry point** — `com.iluwatar.abstractfactory.App`

## Data access — 12 patterns

**`metadata-mapping`**

- **situation** — when object-to-table mapping is hand-written per class and the same mapping code repeats for every one
- **cause** — hand-written mapping code repeats the same field-to-column pattern per class and drifts as the schema moves
- **principle** — Metadata Mapping Design Pattern is designed to manage the mapping between database records and Java objects in a way that keeps the database schema and object model decoupled and manageable.
- **tags** — Decoupling, Enterprise patterns, Object mapping, Persistence
- **entry point** — `com.iluwatar.metamapping.App`

## Functional — 8 patterns

**`callback`**

- **situation** — when a routine must run code supplied by its caller at a point the caller cannot reach
- **cause** — the point where caller-supplied work must run is inside a routine the caller cannot reach
- **principle** — The Java Callback Design Pattern is a piece of executable code passed as an argument to other code, which is expected to call back (execute) the argument at a convenient time.
- **tags** — Asynchronous, Decoupling, Idiom, Reactive
- **entry point** — `com.iluwatar.callback.App`

## Integration — 6 patterns

**`ambassador`**

- **situation** — when calls to a remote service need retry, timeout, logging or monitoring added without touching the calling code
- **cause** — resilience concerns (retry, timeout, monitoring) do not belong to the calling code, and inlining them duplicates the same handling at every call site
- **principle** — The Ambassador Pattern in Java helps offload common functionalities such as monitoring, logging, and routing from a shared resource to a helper service instance, enhancing performance and maintainability in distributed systems.
- **tags** — API design, Decoupling, Fault tolerance, Proxy, Resilience, Scalability
- **entry point** — `com.iluwatar.ambassador.App`

## Resilience — 6 patterns

**`circuit-breaker`**

- **situation** — when calls to a failing dependency keep being made and each one costs time before failing anyway
- **cause** — every call to a failing dependency pays the full timeout before failing, so the failure's cost multiplies instead of being cut off
- **principle** — The Circuit Breaker pattern is a critical Java design pattern that helps ensure fault tolerance and resilience in microservices and distributed systems.
- **tags** — Cloud distributed, Fault tolerance, Microservices, Retry
- **entry point** — `com.iluwatar.circuitbreaker.App`

## Messaging — 4 patterns

**`data-bus`**

- **situation** — when unrelated components must exchange events without knowing about each other
- **cause** — direct references between communicating components form a web that must be rewired for every new participant
- **principle** — The Data Bus design pattern aims to provide a centralized communication channel through which various components of a system can exchange data without being directly connected, thus promoting loose coupling and enhancing scalability and maintainability.
- **tags** — Decoupling, Event-driven, Messaging, Publish/subscribe, Scalability
- **entry point** — `com.iluwatar.databus.App`

## Testing — 4 patterns

**`arrange-act-assert`**

- **situation** — when a test's setup, action and verification run together and a reader cannot tell which line is being tested
- **cause** — a test whose setup, action and verification are interleaved cannot show which behaviour it pins, so failures do not localize
- **principle** — The Arrange/Act/Assert pattern is essential in unit testing in Java.
- **tags** — Code simplification, Isolation, Testing
- **entry point** — `com.iluwatar.arrangeactassert.Cash`

## Performance optimization — 3 patterns

**`caching`**

- **situation** — when the same expensive result is requested repeatedly and recomputing it dominates cost
- **cause** — the same expensive computation repeats with unchanged inputs, so cost scales with requests rather than with change
- **principle** — The Java Caching Design Pattern is crucial for performance optimization and resource management.
- **tags** — Caching, Data access, Performance, Resource management
- **entry point** — `com.iluwatar.caching.App`

## Resource management — 3 patterns

**`resource-acquisition-is-initialization`**

- **situation** — when a resource must be released exactly once, on every path out of a scope
- **cause** — release coded per exit path misses the path someone adds later, and the resource leaks exactly then
- **principle** — Ensure efficient Java resource management by tying the resource lifecycle to object lifetime, utilizing the RAII pattern.
- **tags** — Encapsulation, Memory management, Resource management
- **entry point** — `com.iluwatar.resource.acquisition.is.initialization.App`

## Idiom — 1 pattern

**`immutable`**

- **situation** — when an object is shared across threads or used as a map key and its state must never change after construction
- **cause** — shared mutable state can change under any holder, so every reader needs synchronisation and no key is stable
- **principle** — Ensure that an object's state cannot be changed after it is constructed, making it inherently thread-safe and easier to reason about.
- **tags** — Immutability, Thread safety, Concurrency, Object composition
- **entry point** — `com.iluwatar.immutable.App`

## Service Discovery — 1 pattern

**`microservices-self-registration`**

- **situation** — when instances come and go and their addresses cannot be configured in advance
- **cause** — instances appear and disappear at run time, so any static address list is stale by construction
- **principle** — The intent of the Self-Registration pattern is to enable microservices to automatically announce their presence and location to a central registry (like Eureka) upon startup, simplifying service discovery and allowing other services to find and communicate with them without manual configuration or hardcoded addresses.
- **tags** — Microservices, Self-Registration, Service Discovery, Eureka, Spring Boot, Spring Cloud, Java, Dynamic Configuration, Resilience
- **entry point** — `com.learning.contextservice.ContextserviceApplication`

---

## The read

Verdict options: **go** (ingest all 187) · **go with named exceptions** · **stop** (and say what is wrong).

| who | when | verdict |
|---|---|---|
|  |  |  |
