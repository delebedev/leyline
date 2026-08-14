package leyline.bridge.coord

internal var MatchCutCoordinator.beforeBlockingInstall: (() -> Unit)?
    get() = interactions.beforeInstall
    set(value) {
        interactions.beforeInstall = value
    }

internal var MatchCutCoordinator.beforeBlockingTimeoutClaim: (() -> Unit)?
    get() = interactions.beforeTimeoutClaim
    set(value) {
        interactions.beforeTimeoutClaim = value
    }

internal var MatchCutCoordinator.afterBlockingMaterialization: (() -> Unit)?
    get() = interactions.afterMaterialization
    set(value) {
        interactions.afterMaterialization = value
    }

internal var MatchCutCoordinator.beforeActionEnqueue: (() -> Unit)?
    get() = actions.beforeEnqueue
    set(value) {
        actions.beforeEnqueue = value
    }

internal var MatchCutCoordinator.beforeActionInstall: (() -> Unit)?
    get() = actions.beforeInstall
    set(value) {
        actions.beforeInstall = value
    }

internal var MatchCutCoordinator.afterActionInstall: (() -> Unit)?
    get() = actions.afterInstall
    set(value) {
        actions.afterInstall = value
    }

internal var MatchCutCoordinator.beforeActionPublished: (() -> Unit)?
    get() = actions.beforePublished
    set(value) {
        actions.beforePublished = value
    }

internal var MatchCutCoordinator.beforeActionTimeoutClaim: (() -> Unit)?
    get() = actions.beforeTimeoutClaim
    set(value) {
        actions.beforeTimeoutClaim = value
    }

internal var MatchCutCoordinator.beforeSynchronizationTimeoutClaim: (() -> Unit)?
    get() = actions.beforeSynchronizationTimeoutClaim
    set(value) {
        actions.beforeSynchronizationTimeoutClaim = value
    }
