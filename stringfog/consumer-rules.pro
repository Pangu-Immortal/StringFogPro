# StringFogRuntime 由字节码插件直接插入 INVOKESTATIC 调用，必须保持类名和方法签名稳定。
-keep class com.vapro.vae.stringfog.StringFogRuntime {
    public static java.lang.String decrypt(java.lang.String);
}
