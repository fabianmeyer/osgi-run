package com.athaydes.gradle.osgi

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

import static java.util.Collections.emptyList

/**
 *
 */
class OsgiRuntimeTaskCreator {

    static final Logger log = Logging.getLogger( OsgiRuntimeTaskCreator )

    Closure createOsgiRuntimeTask( Project project, OsgiConfig osgiConfig ) {
        return {
            String target = getTarget( project, osgiConfig )
            osgiConfig.outDirFile = target as File
            log.info( "Will copy osgi runtime resources into $target" )
            configBundles( project, osgiConfig )
            copyBundles( project, "${target}/${osgiConfig.bundlesPath}" )
            configMainDeps( project, osgiConfig )
            copyMainDeps( project, target )
            copyConfigFiles( target, osgiConfig )
        }
    }

    private void configMainDeps( Project project, OsgiConfig osgiConfig ) {
        def hasOsgiMainDeps = !project.configurations.osgiMain.dependencies.empty
        if ( !hasOsgiMainDeps ) {
            assert osgiConfig.osgiMain, 'No osgiMain provided, cannot create OSGi runtime'
            project.dependencies.add( 'osgiMain', osgiConfig.osgiMain )
        }
    }

    private void copyMainDeps( Project project, String target ) {
        project.copy {
            from project.configurations.osgiMain
            into target
        }
    }

    private void configBundles( Project project, OsgiConfig osgiConfig ) {
        osgiConfig.bundles.flatten().each {
            project.dependencies.add( 'osgiRuntime', it )
        }
    }

    private void copyBundles( Project project, String bundlesDir ) {
        project.copy {
            from project.configurations.osgiRuntime
            into bundlesDir
        }
        nonBundles( new File( bundlesDir ).listFiles() ).each {
            println "Non Bundle: ${it.name}"
            assert it.delete()
        }
    }

    private Collection<File> nonBundles( File[] files ) {
        if ( !files ) return emptyList()
        def notBundle = { File file ->
            def zip = new ZipFile( file )
            try {
                ZipEntry entry = zip.getEntry( 'META-INF/MANIFEST.MF' )
                if ( !entry ) return true
                def lines = zip.getInputStream( entry ).readLines()
                return !lines.any { it.trim().startsWith( 'Bundle' ) }
            } finally {
                zip.close()
            }
        }
        files.findAll( notBundle )
    }

    private void copyConfigFiles( String target, OsgiConfig osgiConfig ) {
        def configFile = getConfigFile( target, osgiConfig )
        if ( !configFile ) return;
        if ( !configFile.exists() ) {
            configFile.parentFile.mkdirs()
        }
        configFile.write( scapeSlashes( textForConfigFile( target, osgiConfig ) ), 'UTF-8' )
    }

    private File getConfigFile( String target, OsgiConfig osgiConfig ) {
        switch ( osgiConfig.configSettings ) {
            case 'felix': return new File( "${target}/conf/config.properties" )
            case 'equinox': return new File( "${target}/configuration/config.ini" )
            case 'none': return null
        }
        throw new GradleException( "Unknown OSGi configSettings: ${osgiConfig.configSettings}" )
    }

    private String getTarget( Project project, OsgiConfig osgiConfig ) {
        ( osgiConfig.outDir instanceof File ) ?
                osgiConfig.outDir.absolutePath :
                "${project.buildDir}/${osgiConfig.outDir}"
    }

    private String scapeSlashes( String string ) {
        string.replace( '\\', '\\\\' )
    }

    private String textForConfigFile( String target, OsgiConfig osgiConfig ) {
        switch ( osgiConfig.configSettings ) {
            case 'felix': return generateFelixConfigFile( osgiConfig )
            case 'equinox': return generateEquinoxConfigFile( target, osgiConfig )
            default: throw new GradleException( 'Internal Plugin Error! Unknown configSettings. Please report bug at ' +
                    'https://github.com/renatoathaydes/osgi-run/issues' )
        }
    }

    private String generateFelixConfigFile( OsgiConfig osgiConfig ) {
        map2properties osgiConfig.config
    }

    private String generateEquinoxConfigFile( String target, OsgiConfig osgiConfig ) {
        def bundlesDir = "${target}/${osgiConfig.bundlesPath}" as File
        if ( !bundlesDir.exists() ) {
            bundlesDir.mkdirs()
        }
        def bundleJars = new FileNameByRegexFinder().getFileNames(
                bundlesDir.absolutePath, /.+\.jar/ )
        map2properties( osgiConfig.config +
                [ 'osgi.bundles': bundleJars.collect { it + '@start' }.join( ',' ) ] )
    }

    private String map2properties( Map map ) {
        map.inject( '' ) { acc, key, value ->
            "${acc}${key} = ${value}\n"
        }
    }

}
