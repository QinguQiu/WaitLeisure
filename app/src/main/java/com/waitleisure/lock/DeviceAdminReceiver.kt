package com.waitleisure.lock

/**
 * 设备管理员接收器。
 *
 * 类名与父类同名，因此父类必须写全限定名。
 * 这里不需要重写任何回调 —— 我们只利用「管理员处于激活状态时系统会禁止卸载」这一副作用，
 * 从而避免锁机中途被一键解除。
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver()
