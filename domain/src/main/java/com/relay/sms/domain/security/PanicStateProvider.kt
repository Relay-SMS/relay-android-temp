package com.relay.sms.domain.security

/**
 * Port domaine : la session courante est-elle un déverrouillage **panic-decoy** ?
 *
 * Les use-cases d'urgence ([com.relay.sms.domain.usecase.IAmOkUseCase],
 * [com.relay.sms.domain.usecase.TriggerEmergencyUseCase],
 * [com.relay.sms.domain.usecase.TriggerSafetyCallUseCase]) refusent d'agir sous une session
 * decoy (un agresseur en decoy ne doit pas pouvoir déclencher / annuler un flux d'urgence). Ils ne
 * lisent qu'une capacité booléenne ponctuelle, pas le flux d'état complet.
 *
 * L'implémentation [com.relay.sms.security.AppLockManager] expose l'état de verrouillage complet
 * (`StateFlow<LockState>`) à l'UI et à la couche security ; ce port n'en projette que la question
 * decoy (ségrégation d'interface).
 */
interface PanicStateProvider {

    /** `true` si l'application est actuellement déverrouillée en mode panic-decoy. */
    val isPanicDecoyActive: Boolean
}
