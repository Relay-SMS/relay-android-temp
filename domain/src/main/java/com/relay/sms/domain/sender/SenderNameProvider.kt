package com.relay.sms.domain.sender

/**
 * Port domaine : résout le nom personnel à inclure dans les SMS de réaction sortants.
 *
 * [com.relay.sms.domain.usecase.SendReactionUseCase] l'utilise pour préfixer le format
 * lisible ("<Nom> a réagi par ❤️…") ; `null` = bascule sur le format anonyme. L'implémentation
 * [com.relay.sms.data.sender.SenderNameProviderImpl] lit l'override Settings puis le profil
 * "moi" `ContactsContract.Profile` (Android) et sanitise le nom pour le SMS — `domain/` reste sans
 * import Android ni data.
 */
interface SenderNameProvider {

    /**
     * Retourne le nom à utiliser dans les SMS de réaction sortants, ou `null` si aucune source
     * (override Settings, profil Android) n'a fourni de nom utilisable.
     */
    fun resolveDisplayName(): String?
}
