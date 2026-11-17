# Credential Manager loads its Play Services provider by reflection.
-if class androidx.credentials.CredentialManager
-keep class * extends androidx.credentials.CredentialProvider { *; }
