/*
 * jPSXdec: PlayStation 1 Media Decoder/Converter in Java
 * Copyright (C) 2012-2017  Michael Sabin
 * All rights reserved.
 *
 * Redistribution and use of the jPSXdec code or any derivative works are
 * permitted provided that the following conditions are met:
 *
 *  * Redistributions may not be sold, nor may they be used in commercial
 *    or revenue-generating business activities.
 *
 *  * Redistributions that are modified from the original source must
 *    include the complete source code, including the source code for all
 *    components used by a binary built from the modified sources. However, as
 *    a special exception, the source code distributed need not include
 *    anything that is normally distributed (in either source or binary form)
 *    with the major components (compiler, kernel, and so on) of the operating
 *    system on which the executable runs, unless that component itself
 *    accompanies the executable.
 *
 *  * Redistributions must reproduce the above copyright notice, this list
 *    of conditions and the following disclaimer in the documentation and/or
 *    other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 * IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package jpsxdec.discitems;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.sound.sampled.AudioFormat;
import jpsxdec.audio.SpuAdpcmDecoder;
import jpsxdec.i18n.I;
import jpsxdec.i18n.ILocalizedMessage;
import jpsxdec.sectors.IdentifiedSector;
import jpsxdec.sectors.SectorCrusader;
import jpsxdec.util.BinaryDataNotRecognized;
import jpsxdec.util.ExposedBAOS;
import jpsxdec.util.Fraction;
import jpsxdec.util.IO;
import jpsxdec.util.ILocalizedLogger;
import jpsxdec.util.LoggedFailure;

/** Demultiplexes audio and video from Crusader: No Remorse movies.
 * Can be used for decoding or for discovery (indexing) of when movies start and end.
 *
 * <h3>Use for decoding</h3>
 * Listeners must be set before using this object.
 * The object's life ends at the end of a movie and should be discarded.
 *
 * <h3>Use for indexing</h3>
 * Only the video listener should be set. Dimensions are found dynamically.
 * Create a new demuxer once a movie ends.
 */
public class CrusaderDemuxer implements ISectorFrameDemuxer, ISectorAudioDecoder {

    private static final Logger LOG = Logger.getLogger(CrusaderDemuxer.class.getName());

    private static final boolean DEBUG = false;

    private static final long AUDIO_ID = 0x08000200L;
    
    private static final int CRUSADER_SAMPLES_PER_SECOND = 22050;
    private static final int SAMPLES_PER_SECTOR = CRUSADER_SAMPLES_PER_SECOND / 150;
    static {
        if (CRUSADER_SAMPLES_PER_SECOND % 150 != 0)
            throw new RuntimeException("Crusader sample rate doesn't cleanly divide by sector rate");
    }
    private static final int BYTES_PER_SAMPLE = 4;

    private static enum ReadState {
        MAGIC,
        LENGTH,
        PAYLOAD,
        HEADER1,
        HEADER2,
    }
    
    private static enum PayloadType {
        MDEC,
        AD20,
        AD21,
    }

    /** If this object was constructed for indexing. */
    private final boolean _blnIndexing;

    @Nonnull
    private ReadState _state = ReadState.MAGIC;

    // .. info about the movie ..

    /** Will be dynamic if indexing. */
    private int _iWidth, _iHeight;
    /** Will be dynamic if indexing. */
    private int _iStartSector, _iEndSector;

    // .. payload parsing ..

    @CheckForNull
    private PayloadType _ePayloadType;
    /** The starting offset in the sector of the first payload. */
    private int _iPayloadStartOffset;
    /** Size of the payload as read from the payload header. */
    private int _iPayloadSize;
    /** Remaining payload to read before it is finished. */
    private int _iRemainingPayload = -1;

    /** Saves payload header. */
    private final byte[] _abHeader = new byte[8];

    // .. stateful tracking ...................................................

    /** Crusader identified sector number of the last seen Crusader sector.
     * Important for determining if any sectors were skipped. */
    private int _iPreviousCrusaderSectorNumber = -1;
    /** Tracks if a at least 1 payload was discovered. */
    private boolean _blnFoundAPayload = false;
    /** All presentation sectors are adjusted by this offset.
     * Initialized upon receiving the first payload.
     * This could be initialized differently depending on if someone is
     * listening for audio or video. Not needed for indexing. */
    private int _iInitialPresentationSector = -1;

    /** Saved payload sectors. */
    private final ArrayList<SectorCrusader> _sectors = new ArrayList<SectorCrusader>();

    private final FrameNumber.FactoryWithHeader _frameNumberFactory = new FrameNumber.FactoryWithHeader();

    // ........................................................................

    /** Must be set before feeding sectors. */
    @CheckForNull
    private ICompletedFrameListener _frameListener;
    /** Must be set before feeding sectors, unless indexing. */
    @CheckForNull
    private ISectorTimedAudioWriter _audioListener;

    @CheckForNull
    private final AudioFormat _audioFmt;
    
    public CrusaderDemuxer(int iWidth, int iHeight, int iStartSector, int iEndSector) {
        _iWidth = iWidth;
        _iHeight = iHeight;
        _iStartSector = iStartSector;
        _iEndSector = iEndSector;
        _blnIndexing = false;
        _audioFmt = new AudioFormat(CRUSADER_SAMPLES_PER_SECOND, 16, 2, true, false);
    }

    /** Constructor used for indexing. It will discover the dimensions and
     * start/end sector when it finds it. */
    public CrusaderDemuxer() {
        _iWidth = -1;
        _iHeight = -1;
        _iStartSector = -1;
        _iEndSector = -1;
        _blnIndexing = true;
        _audioFmt = null;
    }
    
    public int getStartSector() {
        return _iStartSector;
    }

    public int getEndSector() {
        return _iEndSector;
    }

    /** Returns if a at least 1 payload was discovered. Even if Crusader
     * sectors are found, there's no need to create a disc item if there's
     * never a payload. */
    public boolean foundAPayload() {
        return _blnFoundAPayload;
    }

    public boolean feedSector(@Nonnull IdentifiedSector identifiedSector, @Nonnull ILocalizedLogger log) throws LoggedFailure {
        
        if (!(identifiedSector instanceof SectorCrusader))
            return false;
        
        if (_blnIndexing && _iStartSector == -1)
            _iStartSector = identifiedSector.getSectorNumber();
        else if (identifiedSector.getSectorNumber() < _iStartSector)
            return false;
        if (!_blnIndexing && identifiedSector.getSectorNumber() > _iEndSector)
            return false;
        
        SectorCrusader cru = (SectorCrusader) identifiedSector;

        // make sure the Crusader identified sector number is part of the same movie
        // note that the math here assumes _iPreviousCrusaderSectorNumber == -1
        // when no Crusader sectors have been encountered
        if (_iPreviousCrusaderSectorNumber != -1 && cru.getCrusaderSectorNumber() < _iPreviousCrusaderSectorNumber) {
            return false; // tell caller to start a new disc item
        } else if (_iPreviousCrusaderSectorNumber + 1 != cru.getCrusaderSectorNumber()) { // check for skipped sectors
            for (int iMissingSector = _iPreviousCrusaderSectorNumber + 1; 
                 iMissingSector < cru.getCrusaderSectorNumber(); 
                 iMissingSector++)
            {
                LOG.log(Level.WARNING, "Missing sector {0,number,#} while demuxing Crusader", iMissingSector);
                feedSectorIteration(null, log);
            }
        }
        
        feedSectorIteration(cru, log);
        
        if (_blnIndexing)
            _iEndSector = identifiedSector.getSectorNumber();
        _iPreviousCrusaderSectorNumber = cru.getCrusaderSectorNumber();
        return true;
    }

    /** Feed either a real sector or null if a sector is missing. */
    private void feedSectorIteration(@CheckForNull SectorCrusader cru, @Nonnull ILocalizedLogger log) throws LoggedFailure {
        int iCurSectorOffset = 0;
        
        while (iCurSectorOffset < SectorCrusader.CRUSADER_IDENTIFIED_USER_DATA_SIZE) {
            switch (_state) {
                case MAGIC:
                    if (cru == null)
                        return;
                    String sBlockType = cru.readMagic(iCurSectorOffset);
                    iCurSectorOffset += 4;
                    _state = ReadState.LENGTH;
                    if ("MDEC".equals(sBlockType))
                        _ePayloadType = PayloadType.MDEC;
                    else if ("ad20".equals(sBlockType))
                        _ePayloadType = PayloadType.AD20;
                    else if ("ad21".equals(sBlockType))
                        _ePayloadType = PayloadType.AD21;
                    else
                        _state = ReadState.MAGIC;
                    break;
                case LENGTH:
                    if (cru == null) {
                        _state = ReadState.MAGIC;
                        return;
                    }
                    _iPayloadSize = cru.readSInt32BE(iCurSectorOffset);
                    _iRemainingPayload = _iPayloadSize - 8;
                    iCurSectorOffset += 4;
                    _state = ReadState.HEADER1;
                    break;
                case HEADER1:
                    if (cru == null) {
                        _state = ReadState.MAGIC;
                        return;
                    }
                    cru.copyIdentifiedUserData(iCurSectorOffset, _abHeader, 0, 4);
                    _iRemainingPayload -= 4;
                    iCurSectorOffset += 4;
                    _state = ReadState.HEADER2;
                    break;
                case HEADER2:
                    if (cru == null) {
                        _state = ReadState.MAGIC;
                        return;
                    }
                    cru.copyIdentifiedUserData(iCurSectorOffset, _abHeader, 4, 4);
                    _iRemainingPayload -= 4;
                    iCurSectorOffset += 4;
                    _state = ReadState.PAYLOAD;
                    break;
                case PAYLOAD:
                    _blnFoundAPayload = true;
                    
                    if (_sectors.isEmpty())
                        _iPayloadStartOffset = iCurSectorOffset;
                    _sectors.add(cru); // add the sector even if it's null, letting the payload handle null sectors
                    
                    int iDataLeftInSector = SectorCrusader.CRUSADER_IDENTIFIED_USER_DATA_SIZE - iCurSectorOffset;
                    if (_iRemainingPayload <= iDataLeftInSector) {
                        // payload is done
                        
                        if (_ePayloadType == PayloadType.MDEC) {
                            videoPayload(_iPayloadSize - 16, log);
                        } else {
                            audioPayload(_iPayloadSize - 16, log);
                        }
                        _sectors.clear();
                        
                        iCurSectorOffset += _iRemainingPayload;
                        _iRemainingPayload = -1;
                        _state = ReadState.MAGIC;
                    } else {
                        _iRemainingPayload -= iDataLeftInSector;
                        iCurSectorOffset += iDataLeftInSector;
                    }
                    break;
            }
        }
    }
    
    public void flush(@Nonnull ILocalizedLogger log) throws LoggedFailure {
        if (!_sectors.isEmpty() && _state == ReadState.PAYLOAD) {
            _blnFoundAPayload = true;
            if (_ePayloadType == PayloadType.MDEC) {
                videoPayload(_iPayloadSize - 16 - _iRemainingPayload, log);
            } else {  // ad20, ad21 (audio)
                audioPayload(_iPayloadSize - 16 - _iRemainingPayload, log);
            }
            _sectors.clear();
        }
        _state = ReadState.MAGIC;
    }
    
    //-- Video stuff ---------------------------
    
    private void videoPayload(int iSize, @Nonnull ILocalizedLogger log) throws LoggedFailure {
        
        int iWidth = IO.readSInt16BE(_abHeader, 0);
        int iHeight = IO.readSInt16BE(_abHeader, 2);
        int iFrame = IO.readSInt32BE(_abHeader, 4);
        
        if (_blnIndexing && _iWidth == -1)
            _iWidth = iWidth;
        if (_blnIndexing && _iHeight == -1)
            _iHeight = iHeight;

        if (_iWidth != iWidth || _iHeight != iHeight) {
            LOG.log(Level.SEVERE, "Crusaer inconsistent dimensions: {0}x{1} != {2}x{3}",
                                  new Object[]{iWidth, iHeight, _iWidth, _iHeight});
            throw new LoggedFailure(log, Level.SEVERE, I.CRUSADER_VIDEO_CORRUPTED());
        }
        if (iFrame < 0) {
            LOG.log(Level.SEVERE, "Crusader bad frame number: {0}", iFrame);
            throw new LoggedFailure(log, Level.SEVERE, I.CRUSADER_VIDEO_CORRUPTED());
        }

        if (_iInitialPresentationSector < 0) {
            _iInitialPresentationSector = iFrame * 10;
            if (_iInitialPresentationSector != 0)
                LOG.log(Level.WARNING, "[Video] Setting initial presentation sector {0,number,#}", _iInitialPresentationSector);
        }

        int iPresentationSector = iFrame * 10 - _iInitialPresentationSector;

        if (DEBUG)
            System.out.format( "[Frame %d] Presentation sector %d Size %d Start %d.%d End %d", 
                    iFrame, iPresentationSector, iSize,
                    _sectors.get(0).getSectorNumber(), _iPayloadStartOffset,
                    _sectors.get(_sectors.size()-1).getSectorNumber() ).println();
        
        SectorCrusader[] aoSects = _sectors.toArray(new SectorCrusader[_sectors.size()]);
        FrameNumber hdrFrame = _frameNumberFactory.next(_sectors.get(0).getSectorNumber(), iFrame);
        if (DEBUG)
            System.out.format("Writing frame %d to be presented at sector %d", iFrame, _iStartSector + iPresentationSector).println();
        if (_frameListener == null)
            throw new IllegalStateException("Frame listener must be set before using object.");
        _frameListener.frameComplete(new DemuxedCrusaderFrame(_iWidth, _iHeight, 
                                                              aoSects, iSize, 
                                                              _iPayloadStartOffset, 
                                                              hdrFrame, _iStartSector + iPresentationSector));
    }
    
    public int getHeight() {
        return _iHeight;
    }

    public int getWidth() {
        return _iWidth;
    }

    public void setFrameListener(@Nonnull ICompletedFrameListener listener) {
        _frameListener = listener;
    }

    //-- Audio stuff ---------------------------

    @CheckForNull
    private byte[] _abAudioDemuxBuffer;
    private final SpuAdpcmDecoder.Stereo _audDecoder = new SpuAdpcmDecoder.Stereo(1.0);
    
    private void audioPayload(final int iSize, @Nonnull ILocalizedLogger log) throws LoggedFailure {

        if (iSize % 2 != 0)
            throw new IllegalArgumentException("Crusader uneven audio payload size " + iSize);
        
        // .. read the header values ...............................
        final long lngPresentationSample = IO.readSInt32BE(_abHeader, 0);
        if (lngPresentationSample < 0) {
            LOG.log(Level.SEVERE, "Crusader invalid presentation sample: {0}",
                                  lngPresentationSample);
            throw new LoggedFailure(log, Level.SEVERE, I.CRUSADER_AUDIO_CORRUPTED());
        }
        final long lngAudioId = IO.readUInt32BE(_abHeader, 4);
        if (lngAudioId != AUDIO_ID) {
            LOG.log(Level.SEVERE, "Crusader invalid audio id: {0}",
                                  lngAudioId);
            throw new LoggedFailure(log, Level.SEVERE, I.CRUSADER_AUDIO_CORRUPTED());
        }

        // .. copy the audio data out of the sectors ...............
        if (_abAudioDemuxBuffer == null || _abAudioDemuxBuffer.length < iSize)
            _abAudioDemuxBuffer = new byte[iSize];
        else
            // pre-fill the buffer with 0 in case we are missing sectors at end
            Arrays.fill(_abAudioDemuxBuffer, 0, iSize, (byte)0);

        int iFirstPayloadSector = -1; // keep track of the first sector of the payload for logging

        for (int iBufferPos = 0, iSect = 0; iSect < _sectors.size(); iSect++) {
            int iBytesToCopy;
            if (iSect == 0)
                iBytesToCopy = SectorCrusader.CRUSADER_IDENTIFIED_USER_DATA_SIZE - _iPayloadStartOffset;
            else
                iBytesToCopy = SectorCrusader.CRUSADER_IDENTIFIED_USER_DATA_SIZE;
            
            // only copy as much as we need from the last sector(s)
            if (iBufferPos + iBytesToCopy > iSize)
                iBytesToCopy = iSize - iBufferPos;
            if (iBytesToCopy == 0)
                break;
            
            SectorCrusader chunk = _sectors.get(iSect);
            if (chunk != null) {
                if (iSect == 0)
                    chunk.copyIdentifiedUserData(_iPayloadStartOffset, _abAudioDemuxBuffer, iBufferPos, iBytesToCopy);
                else
                    chunk.copyIdentifiedUserData(0, _abAudioDemuxBuffer, iBufferPos, iBytesToCopy);
                if (iFirstPayloadSector == -1)
                    iFirstPayloadSector = chunk.getSectorNumber();
            } else {
                LOG.log(Level.WARNING, "Missing sector {0,number,#} from Crusader audio data", iSect);
                // just skip the bytes that would have been copied
                // they were already previously set to 0
            }
            iBufferPos += iBytesToCopy;
        }

        // if the initial portion of a movie is missing, and audio is the first
        // payload found, we need to adjust the initial presentation offset
        // so we don't write a ton silence initially in an effort to catch up.
        // it seems audio payload presentation sectors run about 40 sectors
        // ahead of the video presentation sectors.
        // so pick an initial presentation sector a little before when the next
        // frame should be presented
        if (_iInitialPresentationSector < 0) {
            _iInitialPresentationSector = (int)(lngPresentationSample / SAMPLES_PER_SECTOR) - 60;
            if (_iInitialPresentationSector < 0) // don't start before the start of the movie
                _iInitialPresentationSector = 0;
            else if (_iInitialPresentationSector > 0)
                LOG.log(Level.WARNING, "[Audio] Setting initial presentation sector {0,number,#}", _iInitialPresentationSector);
        }
        
        // .. decode the audio data .............................
        ExposedBAOS audioBuffer = new ExposedBAOS();
        {
            int iChannelSize = iSize / 2; // size is already confirmed to be divisible by 2
            try {
                _audDecoder.decode(new ByteArrayInputStream(_abAudioDemuxBuffer, 0, iChannelSize),
                                   new ByteArrayInputStream(_abAudioDemuxBuffer, iChannelSize, iChannelSize),
                                   iChannelSize, audioBuffer);
            } catch (IOException ex) {
                throw new RuntimeException("Should never happen", ex);
            }
            if (_audDecoder.hadCorruption())
                log.log(Level.WARNING, I.SPU_ADPCM_CORRUPTED(iFirstPayloadSector, _audDecoder.getSampleFramesWritten()));
        }

        if (!_blnIndexing) {

            if (_audioListener == null)
                throw new IllegalStateException("Audio listener must be set before using object.");
            Fraction presentationSector = new Fraction(lngPresentationSample, SAMPLES_PER_SECTOR);
            if (DEBUG)
                System.out.format("Writing %d bytes of audio to be presented at sector %s", audioBuffer.size(), presentationSector).println();
            _audioListener.write(_audioFmt, audioBuffer.getBuffer(), 0, audioBuffer.size(), presentationSector);
        }

    }
    
    
    /** Must be set before feeding sectors, unless indexing. */
    public void setAudioListener(@Nonnull ISectorTimedAudioWriter audioFeeder) {
        if (_blnIndexing)
            throw new IllegalArgumentException("Adding audio listener during indexing?");
        _audioListener = audioFeeder;
    }

    public int getPresentationStartSector() {
        return _iStartSector; 
   }

    public @Nonnull AudioFormat getOutputFormat() {
        if (_blnIndexing)
            throw new UnsupportedOperationException("Object being used for indexing");
        return _audioFmt;
    }

    public int getSamplesPerSecond() {
        return CRUSADER_SAMPLES_PER_SECOND;
    }
    
    public int getDiscSpeed() {
        return 2;
    }

    public double getVolume() {
        return _audDecoder.getVolume();
    }

    public void setVolume(double dblVolume) {
        _audDecoder.setVolume(dblVolume);
    }

    public void reset() {
        _audDecoder.resetContext();
    }
    
    public @Nonnull ILocalizedMessage[] getAudioDetails() {
        return new ILocalizedMessage[] {I.EMBEDDED_CRUSADER_AUDIO_HZ(CRUSADER_SAMPLES_PER_SECOND)};
    }
    
}
