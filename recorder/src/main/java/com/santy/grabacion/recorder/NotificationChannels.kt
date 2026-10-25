package com.santy.grabacion.recorder

/**
 * Convención de canales de notificación por módulo. Los canales nuevos usan estos prefijos;
 * el canal existente del vault ("captura", en CameraService) se conserva con su id original
 * para no perder la configuración que el usuario ya le dio.
 */
object NotificationChannels {
    const val MAIN_PREFIX = "main-"
    const val VAULT_PREFIX = "vault-"
}
