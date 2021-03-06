package org.esp.publisher;

import it.jrc.persist.Dao;

import java.awt.image.RenderedImage;
import java.io.File;
import java.io.IOException;

import javax.media.jai.ImageLayout;
import javax.media.jai.RenderedOp;
import javax.media.jai.operator.ExtremaDescriptor;
import javax.persistence.Query;

import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.imageio.IIOMetadataDumper;
import org.geotools.coverage.grid.io.imageio.geotiff.GeoTiffIIOMetadataDecoder;
import org.geotools.gce.geotiff.GeoTiffReader;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Doubles;
import com.google.inject.Inject;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.io.ParseException;
import com.vividsolutions.jts.io.WKTReader;

/**
 * Quick refactor to extract tiff processing for a copy paste job
 * 
 * @author Will Temperley
 * 
 */
public class TiffMetadataExtractor {

    protected Dao dao;

    static {
        System.setProperty("com.sun.media.jai.disableMediaLib", "true");
    }

    @Inject
    public TiffMetadataExtractor(Dao dao) {
        this.dao = dao;
    }

    public boolean extractTiffMetadata(File tifFile, TiffMeta surface)
            throws UnknownCRSException, FactoryException, TransformException,
            IOException {
        
        Preconditions.checkArgument(tifFile != null, "File is null.");

        GeoTiffReader gtr = new GeoTiffReader(tifFile);

        try {

            CoordinateReferenceSystem crs = gtr.getCoordinateReferenceSystem();
            surface.setCRS(crs);
            Integer epsgCode = CRS.lookupEpsgCode(crs, true);

            if (epsgCode == null) {
                ReferenceIdentifier name = crs.getName();
                String crsName = "Unknown";
                if (name != null) {
                    crsName = name.toString();
                }
                throw new UnknownCRSException(crsName);
            }

            String srid = "EPSG:" + epsgCode;
            surface.setSrid(srid);

            //
            // extremaOp(surface, gtr.read(null));

            /*
             * Build the envelope and set to WGS84
             */
            GeneralEnvelope origEnv = gtr.getOriginalEnvelope();
            DirectPosition ll = origEnv.getLowerCorner();
            DirectPosition ur = origEnv.getUpperCorner();

            Envelope e = new Envelope();
            e.expandToInclude(ll.getOrdinate(0), ll.getOrdinate(1));
            e.expandToInclude(ur.getOrdinate(0), ur.getOrdinate(1));

            Geometry poly = envelopeToWgs84(epsgCode, e);

            if (poly instanceof Polygon) {
                surface.setEnvelope((Polygon) poly);
            }

            /*
             * Figure out the pixel size
             */
            ImageLayout imageLayout = gtr.getImageLayout();
            int imageWidth = imageLayout.getWidth(null);
            int imageHeight = imageLayout.getHeight(null);

            double pixelSizeX = e.getWidth() / imageWidth;
            double pixelSizeY = e.getHeight() / imageHeight;

            surface.setPixelSizeX(pixelSizeX);
            surface.setPixelSizeY(pixelSizeY);

            surface.setMinVal(0d);
            surface.setMaxVal(100d);

            GridCoverage2D gridCoverage2D = gtr.read(null);

            try {
                int nDims = gridCoverage2D.getNumSampleDimensions();
                surface.setNumSampleDimensions(nDims);

                extremaOp(surface, gridCoverage2D);

            } finally {
                gridCoverage2D.dispose(false);
            }

        } finally {

            gtr.dispose();

        }

        return true;
    }

    private void extremaOp(TiffMeta surface, GridCoverage2D gridCoverage2D) {
        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;

        RenderedImage img = gridCoverage2D.getRenderedImage();

        RenderedOp extremaOp = ExtremaDescriptor.create(img, null, 10, 10,
                false, 1, null);
        double[] allMins = (double[]) extremaOp.getProperty("minimum");
        min = Doubles.min(allMins);

        double[] allMaxs = (double[]) extremaOp.getProperty("maximum");
        max = Doubles.max(allMaxs);

        surface.setMaxVal(max);
        surface.setMinVal(min);
    }

    protected Polygon envelopeToWgs84(Integer epsgCode, Envelope e)
            throws TransformException {

        Query q = dao
                .getEntityManager()
                .createNativeQuery(
                        "SELECT st_astext(st_transform(st_setsrid(st_makebox2d(st_point(?1,?2), st_point(?3,?4)), ?5), 4326));");
        q.setParameter(1, e.getMinX());
        q.setParameter(2, e.getMinY());
        q.setParameter(3, e.getMaxX());
        q.setParameter(4, e.getMaxY());
        q.setParameter(5, epsgCode);

        String wkt = (String) q.getSingleResult();

        WKTReader w = new WKTReader();
        Geometry poly;
        try {
            poly = w.read(wkt);
            poly.setSRID(4326);
            return (Polygon) poly;
        } catch (ParseException e1) {
            return null;
        }
    }

}