package me.shika.di

import me.shika.BuildInfo
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact

class DiCompilerSubplugin : AbstractDiCompilerSubplugin() {
    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            groupId = "me.shika.di",
            artifactId = "dagger-compiler-plugin",
            version = BuildInfo.VERSION
        )
}
