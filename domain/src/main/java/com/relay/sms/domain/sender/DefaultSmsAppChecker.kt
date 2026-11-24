/*
 * Copyright 2026 Relay Messaging (modifications). The original work is
 * copyright its respective holders and is licensed under the Apache
 * License 2.0. See LICENSE.
 */

package com.relay.sms.domain.sender

/**
 * Port domaine étroit : « Relay est-il l'application SMS par défaut ? ».
 *
 * Les use-cases d'envoi ([com.relay.sms.domain.usecase.SendSmsUseCase],
 * [com.relay.sms.domain.usecase.SendMediaMmsUseCase],
 * [com.relay.sms.domain.usecase.SendVoiceMmsUseCase]) refusent d'émettre si l'app n'est pas
 * l'app SMS par défaut ; ils ne dépendent que de cette capacité booléenne.
 *
 * La construction de l'`Intent` Android de demande de rôle
 * ([com.relay.sms.data.sms.DefaultSmsAppManager.buildChangeDefaultIntent]) reste hors de ce
 * port : c'est un détail Android consommé uniquement par l'UI, qui garde une dépendance directe à
 * l'implémentation (ségrégation d'interface — `domain/` n'importe aucun type Android).
 */
interface DefaultSmsAppChecker {
    fun isDefault(): Boolean
}
